package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.CustomAdjustmentCreateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A hand-made settlement line needs no named category (V62).
 *
 * <p>Before V62 a person adding a line picked one of the named SETTLEMENTS
 * categories and renamed it, so a second "Rata" that was actually an advance
 * still CLAIMED to be an instalment. Now the request simply omits the category,
 * the server hangs the line off the CUSTOM carrier, and the typed name is the
 * whole identity — which is why the name is compulsory there.
 *
 * <p>The carrier itself must never surface as a system row: seed_on_init is
 * FALSE, so initialisation skips it, while the scheme scope still has to know
 * it (D6 demands a rule per scheme, and an unknown category refuses the add).
 */
@Transactional
class CustomSettlementLineIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private PayrollAdjustmentCategoryRepository categoryRepository;
    @Autowired private PayrollRunItemRepository itemRepository;
    @Autowired private PayrollScenarioFixture fixture;

    private static CustomAdjustmentCreateRequest line(String name, String amount) {
        CustomAdjustmentCreateRequest req = new CustomAdjustmentCreateRequest();
        req.setDisplayName(name);
        req.setAmount(amount != null ? new BigDecimal(amount) : null);
        return req;
    }

    @Test
    @DisplayName("a line without a category lands on the CUSTOM carrier and reduces the payout")
    void lineWithoutCategoryLandsOnCarrier() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        BigDecimal paidBefore = nz(scenario.item().getPreviouslyPaidAmount());

        payrollRunItemService.addCustomSettlementLine(itemId, line("Akontacija za letovanje", "5000.00"));

        List<PayrollAdjustment> customs = adjustmentRepository
                .findByItemIdAndSectionCode(itemId, "SETTLEMENTS").stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsCustom()))
                .toList();
        assertThat(customs).singleElement().satisfies(a -> {
            assertThat(a.getPayrollAdjustmentCategory().getCode()).isEqualTo("CUSTOM");
            assertThat(a.getDisplayName()).isEqualTo("Akontacija za letovanje");
            assertThat(a.getAmount()).isEqualByComparingTo("5000.00");
        });

        PayrollRunItem after = itemRepository.findById(itemId).orElseThrow();
        assertThat(nz(after.getPreviouslyPaidAmount()))
                .isEqualByComparingTo(paidBefore.add(new BigDecimal("5000.00")));
    }

    @Test
    @DisplayName("on the carrier the typed name is the identity, so an empty one is refused")
    void emptyNameOnCarrierIsRefused() {
        var scenario = fixture.scenario().build();

        assertThatThrownBy(() ->
                payrollRunItemService.addCustomSettlementLine(scenario.item().getId(), line("  ", "100.00")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Unesite naziv stavke");

        assertThat(adjustmentRepository
                .findByItemIdAndSectionCode(scenario.item().getId(), "SETTLEMENTS").stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsCustom())))
                .isEmpty();
    }

    @Test
    @DisplayName("the carrier exists as V62 seeded it, outside the initialisation set")
    void carrierIsSeededButNeverInitialised() {
        PayrollAdjustmentCategory custom = categoryRepository.findByCode("CUSTOM").orElseThrow();
        assertThat(custom.getSectionCode()).isEqualTo("SETTLEMENTS");
        assertThat(custom.getCalculationKey()).isEqualTo("MANUAL");
        assertThat(custom.getSeedOnInit()).isFalse();

        // The set initialisation seeds on every payslip — the carrier is not in it,
        // or every payroll would be born with an empty "Ručna stavka" row.
        assertThat(categoryRepository.findByIsActiveTrueAndArchivedAtIsNullAndSeedOnInitTrue())
                .noneMatch(c -> "CUSTOM".equals(c.getCode()));
        assertThat(categoryRepository.findByIsActiveTrueAndArchivedAtIsNull())
                .anyMatch(c -> "CUSTOM".equals(c.getCode()));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
