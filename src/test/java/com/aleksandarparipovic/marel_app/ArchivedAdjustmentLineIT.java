package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removing a line from a payroll is an ARCHIVE (owner's call, 2026-10-09):
 * any line may go — system or hand-made — and once gone it reaches no total,
 * no screen, no PDF and no calculator, while the row itself stays on record.
 *
 * <p>The mechanism under test is the repository seam: every per-item read
 * excludes archived rows, while the init's existence check and the partial
 * unique index still see them — so nothing recreates a removed line, and the
 * calculators' {@code ifPresent} writes find nothing to write to.
 */
@Transactional
class ArchivedAdjustmentLineIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollRunItemRepository itemRepository;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("an archived settlement line leaves the payout, the detail response and the section list")
    void archivedSettlementLineLeavesEverything() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        fixture.adjustmentAmount(scenario, "PAID_PART_1", "10000.00");

        PayrollRunItem item = itemRepository.findById(itemId).orElseThrow();
        payrollRunItemService.recalculateSummaryTotals(item);
        BigDecimal paidBefore = item.getPreviouslyPaidAmount();
        assertThat(paidBefore).isGreaterThanOrEqualTo(new BigDecimal("10000.00"));

        payrollRunItemService.archiveAdjustmentLine(itemId, scenario.adjustment("PAID_PART_1").getId());

        PayrollRunItem after = itemRepository.findById(itemId).orElseThrow();
        assertThat(after.getPreviouslyPaidAmount())
                .isEqualByComparingTo(paidBefore.subtract(new BigDecimal("10000.00")));

        // Off the screen and the PDF: the detail response — the one document both
        // render from, and the one the lock freezes — no longer carries the line.
        boolean stillListed = payrollRunItemService.getDetails(scenario.monthlyReport().getId())
                .getAdjustments().stream()
                .flatMap(s -> s.getAdjustments().stream())
                .anyMatch(l -> "PAID_PART_1".equals(l.getCategoryCode()));
        assertThat(stillListed).isFalse();

        // And off the section reads the reorder and the custom-add bottom use.
        assertThat(adjustmentRepository.findByItemIdAndSectionCode(itemId, "SETTLEMENTS"))
                .noneMatch(a -> "PAID_PART_1".equals(a.getPayrollAdjustmentCategory().getCode()));
    }

    @Test
    @DisplayName("an archived earnings line leaves the pay, and the calculators can no longer reach it")
    void archivedEarningsLineLeavesThePayAndTheCalculators() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        fixture.adjustmentAmount(scenario, "OTHER", "2000.00");

        PayrollRunItem item = itemRepository.findById(itemId).orElseThrow();
        payrollRunItemService.recalculateSummaryTotals(item);
        BigDecimal earningsBefore = item.getTotalNetEarnings();

        payrollRunItemService.archiveAdjustmentLine(itemId, scenario.adjustment("OTHER").getId());

        PayrollRunItem after = itemRepository.findById(itemId).orElseThrow();
        assertThat(after.getTotalNetEarnings())
                .isEqualByComparingTo(earningsBefore.subtract(new BigDecimal("2000.00")));

        // THE calculator seam: every automatic write goes through this lookup's
        // ifPresent — empty means no calculator can ever touch the line again.
        assertThat(adjustmentRepository.findByItemIdAndCategoryCode(itemId, "OTHER")).isEmpty();
    }

    @Test
    @DisplayName("a recalculation does not resurrect an archived line, not even one it rewrites every run")
    void recalculationDoesNotResurrectAnArchivedLine() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        // PAID_PREVIOUS_PERIOD is the hardest case: writeDerivedSettlementLines
        // actively syncs it on EVERY recalculation.
        payrollRunItemService.archiveAdjustmentLine(itemId, scenario.adjustment("PAID_PREVIOUS_PERIOD").getId());

        PayrollRunItem item = itemRepository.findById(itemId).orElseThrow();
        payrollRunItemService.recalculateSummaryTotals(item);

        PayrollAdjustment stillArchived = adjustmentRepository
                .findByIdWithCategory(scenario.adjustment("PAID_PREVIOUS_PERIOD").getId()).orElseThrow();
        assertThat(stillArchived.getArchivedAt()).isNotNull();
        assertThat(adjustmentRepository.findByItemIdAndCategoryCode(itemId, "PAID_PREVIOUS_PERIOD")).isEmpty();
    }

    @Test
    @DisplayName("a hand-made line archives the same way, keeping its figures on record")
    void customLineArchivesTheSameWay() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        var req = new com.aleksandarparipovic.marel_app.payroll_run_item.dto.CustomAdjustmentCreateRequest();
        req.setDisplayName("Akontacija");
        req.setAmount(new BigDecimal("1500.00"));
        payrollRunItemService.addCustomSettlementLine(itemId, req);

        PayrollAdjustment custom = adjustmentRepository.findByItemIdAndSectionCode(itemId, "SETTLEMENTS").stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsCustom()))
                .findFirst().orElseThrow();

        payrollRunItemService.archiveAdjustmentLine(itemId, custom.getId());

        // Not deleted: the figures and the trail stay, only the payroll forgets it.
        PayrollAdjustment kept = adjustmentRepository.findByIdWithCategory(custom.getId()).orElseThrow();
        assertThat(kept.getArchivedAt()).isNotNull();
        assertThat(kept.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(adjustmentRepository.findByItemIdAndSectionCode(itemId, "SETTLEMENTS"))
                .noneMatch(a -> Boolean.TRUE.equals(a.getIsCustom()));
    }
}
