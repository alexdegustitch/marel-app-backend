package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollTotals;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V63's whole claim, verified: one direction per section computes the SAME
 * totals the old two-vocabulary formula did (impact_code on the plus side,
 * section_code on the minus side).
 *
 * <p>The claim rests on impact-homogeneity, which the migration's guard block
 * enforces per database. These tests pin the other half: that the code's
 * translation of the old vocabulary ({@link PayrollTotals#legacyDirection})
 * agrees with the seeded directions category by category — the formula is
 * linear in the lines, so direction-equality per category IS totals-equality
 * for every possible payroll — and, belt and braces, that one concrete payroll
 * recomputed through the new formula matches the old arithmetic done by hand
 * over the legacy columns.
 */
@Transactional
class SectionDirectionEquivalenceIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollRunItemRepository itemRepository;
    @Autowired private PayrollRunItemCategoryRepository itemCategoryRepository;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private PayrollAdjustmentCategoryRepository categoryRepository;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("every category's section direction equals what the old formula did with it")
    void directionsAgreeWithTheLegacyVocabulary() {
        fixture.catalogue();

        List<PayrollAdjustmentCategory> all = categoryRepository.findAll();
        assertThat(all).isNotEmpty();
        for (PayrollAdjustmentCategory cat : all) {
            assertThat(cat.getSection().getDirection())
                    .as("category %s (section %s, legacy impact %s)",
                            cat.getCode(), cat.getSection().getCode(), cat.getImpactCode())
                    .isEqualTo(PayrollTotals.legacyDirection(cat.getImpactCode(), cat.getSectionCode()));
        }
    }

    @Test
    @DisplayName("one concrete payroll: the new formula reproduces the old arithmetic to the digit")
    void aConcretePayrollMatchesTheOldArithmetic() {
        var scenario = fixture.scenario().build();

        // Distinguishable figures in every kind of section: PLUS (OTHER,
        // FIXED_SALARY), MINUS (both "Isplaćeno" halves with their historically
        // DIFFERENT impact codes, plus a hand-added custom line), and NONE
        // (current phone, the balance mirror) — so a direction wired wrongly
        // anywhere cannot cancel out.
        fixture.adjustmentAmount(scenario, "OTHER", "2000.00");
        fixture.adjustmentAmount(scenario, "FIXED_SALARY", "40000.00");
        fixture.adjustmentAmount(scenario, "PAID_PART_1", "10000.00");
        fixture.adjustmentAmount(scenario, "PAID_PART_2", "3000.00");
        fixture.adjustmentAmount(scenario, "PHONE_CURRENT_MONTH", "700.00");

        payrollRunItemService.addCustomSettlementLine(scenario.item().getId(),
                customLine("Akontacija", "1500.00"));

        PayrollRunItem item = itemRepository.findById(scenario.item().getId()).orElseThrow();
        payrollRunItemService.recalculateSummaryTotals(item);

        // The OLD formula, computed here by hand from the LEGACY columns.
        List<PayrollAdjustment> lines = adjustmentRepository
                .findByPayrollRunItemIdWithCategory(item.getId());
        BigDecimal categoriesSum = itemCategoryRepository
                .findByPayrollRunItemIdWithWorkCodeCategory(item.getId()).stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal oldEarnings = categoriesSum.add(lines.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && "GROSS_PLUS".equals(a.getPayrollAdjustmentCategory().getImpactCode()))
                .map(PayrollAdjustment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal oldPaid = lines.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && "SETTLEMENTS".equalsIgnoreCase(a.getPayrollAdjustmentCategory().getSectionCode()))
                .map(PayrollAdjustment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        assertThat(item.getTotalNetEarnings()).isEqualByComparingTo(oldEarnings);
        assertThat(item.getPreviouslyPaidAmount()).isEqualByComparingTo(oldPaid);
        // And the custom line really sits inside the minus side it claims.
        assertThat(oldPaid).isGreaterThanOrEqualTo(new BigDecimal("14500.00"));
    }

    private static com.aleksandarparipovic.marel_app.payroll_run_item.dto.CustomAdjustmentCreateRequest
    customLine(String name, String amount) {
        var req = new com.aleksandarparipovic.marel_app.payroll_run_item.dto.CustomAdjustmentCreateRequest();
        req.setDisplayName(name);
        req.setAmount(new BigDecimal(amount));
        return req;
    }
}
