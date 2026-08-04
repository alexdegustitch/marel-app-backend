package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustment;
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_time_adjustment.PayrollTimeAdjustmentRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Time corrections are rows, the way money corrections are.
 *
 * <p>What this replaces is a single signed integer on the item. It could say how
 * many minutes were added and nothing else — not why, not by whom — and it could
 * not hold two corrections with different causes in the same month. Correcting
 * one of two reasons meant recomputing the other in your head first.
 *
 * <p>The table is deliberately NOT payroll_adjustments. Every impact code there
 * moves money into a total, so a minutes row would either be summed into
 * somebody's pay or need a code that every sum-by-impact has to skip.
 */
@Transactional
class PayrollTimeAdjustmentIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollTimeAdjustmentRepository timeAdjustmentRepository;
    @Autowired private PayrollTimeAdjustmentCategoryRepository categoryRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PayrollRunItemPatchRequest patch(Integer minutes, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("manualAdjustedMinutes", minutes);
        if (reason != null) body.put("manualAdjustedMinutesReason", reason);
        return objectMapper.convertValue(body, PayrollRunItemPatchRequest.class);
    }

    private PayrollTimeAdjustmentCategory manualCategory() {
        return categoryRepository
                .findByCode(PayrollTimeAdjustmentCategory.CODE_MANUAL_CORRECTION)
                .orElseThrow();
    }

    // ── the catalogue ───────────────────────────────────────────────────────

    @Test
    @DisplayName("only the correction that already existed is seeded")
    void onlyTheExistingCorrectionIsSeeded() {
        // MISSING_SHIFT, PAID_ABSENCE_CORRECTION and the rest are NOT seeded on
        // purpose: nobody stated them as business rules, and three of them would
        // duplicate records the system already keeps at the source — work_shifts,
        // and monthly_reports together with work_code_categories.type.
        assertThat(categoryRepository.findByIsActiveTrueAndArchivedAtIsNullOrderBySortOrderAscCodeAsc())
                .extracting(PayrollTimeAdjustmentCategory::getCode)
                .containsExactly(PayrollTimeAdjustmentCategory.CODE_MANUAL_CORRECTION);

        assertThat(manualCategory().getRequireReason()).isTrue();
        assertThat(manualCategory().getImpactCode())
                .isEqualTo(PayrollTimeAdjustmentCategory.IMPACT_PAYABLE_MINUTES);
    }

    // ── writing a correction ────────────────────────────────────────────────

    @Test
    @DisplayName("a correction becomes a row carrying its reason and its author")
    void aCorrectionBecomesARow() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        payrollRunItemService.patch(itemId, patch(-240, "Nije bio na poslu 12.09."));

        assertThat(timeAdjustmentRepository.findByItemIdWithCategory(itemId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getMinutes()).isEqualTo(-240);
                    assertThat(row.getReason()).isEqualTo("Nije bio na poslu 12.09.");
                    assertThat(row.getHasManualInput()).isTrue();
                    assertThat(row.getIsApplied()).isTrue();
                    // 0 because MANUAL_CORRECTION has no calculator behind it. The
                    // column exists so an automatic correction can later be told
                    // apart from a person's change to it.
                    assertThat(row.getSystemMinutes()).isZero();
                });
    }

    @Test
    @DisplayName("a correction without a reason is refused")
    void aCorrectionWithoutAReasonIsRefused() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        assertThatThrownBy(() -> payrollRunItemService.patch(itemId, patch(120, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Razlog je obavezan");

        assertThat(timeAdjustmentRepository.findByItemIdWithCategory(itemId)).isEmpty();
    }

    @Test
    @DisplayName("re-saving the same minutes does not demand the reason again")
    void resavingDoesNotDemandTheReasonAgain() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        payrollRunItemService.patch(itemId, patch(120, "Zaboravljena smena"));

        // A form that resubmits every field must not fail because the user did
        // not retype an explanation for a value they did not change.
        payrollRunItemService.patch(itemId, patch(120, null));

        assertThat(timeAdjustmentRepository.findByItemIdWithCategory(itemId))
                .singleElement()
                .satisfies(row -> assertThat(row.getReason()).isEqualTo("Zaboravljena smena"));
    }

    @Test
    @DisplayName("setting the correction to zero withdraws it instead of storing a zero")
    void zeroWithdrawsTheCorrection() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        payrollRunItemService.patch(itemId, patch(120, "Zaboravljena smena"));

        payrollRunItemService.patch(itemId, patch(0, null));

        // findByItemIdWithCategory excludes archived rows: the correction is gone
        // from the payslip but recoverable, and the database would have rejected
        // a stored zero anyway.
        assertThat(timeAdjustmentRepository.findByItemIdWithCategory(itemId)).isEmpty();
        assertThat(timeAdjustmentRepository.sumPayableMinutesFor(itemId)).isZero();
    }

    @Test
    @DisplayName("a zero correction cannot be stored at all")
    void aZeroCorrectionIsRejectedByTheDatabase() {
        var scenario = fixture.scenario().build();

        // No row means no correction. That is why this table needs no equivalent
        // of the show-when-zero problem the money side has.
        //
        // The INSERT leaves for the database inside save(), not at the later
        // flush: GenerationType.IDENTITY has to round-trip to get the id.
        assertThatThrownBy(() -> timeAdjustmentRepository.save(PayrollTimeAdjustment.builder()
                .payrollRunItem(scenario.item())
                .category(manualCategory())
                .systemMinutes(0)
                .minutes(0)
                .reason("nista")
                .isApplied(true)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_pta_minutes_nonzero");
    }

    // ── what it does to the minutes total ───────────────────────────────────

    @Test
    @DisplayName("two corrections with different causes add up")
    void twoCorrectionsAddUp() {
        var scenario = fixture.scenario().workMinutes(10_560).build();
        Long itemId = scenario.item().getId();

        payrollRunItemService.patch(itemId, patch(-240, "Nije bio na poslu 12.09."));
        timeAdjustmentRepository.save(PayrollTimeAdjustment.builder()
                .payrollRunItem(scenario.item())
                .category(manualCategory())
                .systemMinutes(0)
                .minutes(60)
                .hasManualInput(true)
                .reason("Zaokruživanje smene 30.09.")
                .isApplied(true)
                .build());
        entityManager.flush();

        // The single integer on the item could never hold this: correcting one
        // cause meant recomputing the other by hand first.
        assertThat(timeAdjustmentRepository.sumPayableMinutesFor(itemId)).isEqualTo(-180);
    }

    @Test
    @DisplayName("a recalculation takes the total from the rows, not from the column")
    void theRecalculationSumsTheRows() {
        var scenario = fixture.scenario().workMinutes(10_560).build();
        Long itemId = scenario.item().getId();
        payrollRunItemService.patch(itemId, patch(-240, "Nije bio na poslu 12.09."));

        PayrollRunItem stale = payrollRunItemService.findById(itemId);
        stale.setNeedsRecalculation(true);
        entityManager.flush();
        payrollRunItemService.getForPayrollAccess(itemId);

        PayrollRunItem item = payrollRunItemService.findById(itemId);
        assertThat(item.getTotalPayrollMinutes()).isEqualTo(10_560 - 240);
        // Kept in step until phase 7 drops it, exactly as the meal and transport
        // columns are.
        assertThat(item.getManualAdjustedMinutes()).isEqualTo(-240);
    }

    @Test
    @DisplayName("a withdrawn correction stops counting")
    void aWithdrawnCorrectionStopsCounting() {
        var scenario = fixture.scenario().workMinutes(10_560).build();
        Long itemId = scenario.item().getId();
        payrollRunItemService.patch(itemId, patch(-240, "Greška u unosu"));

        PayrollTimeAdjustment row = timeAdjustmentRepository.findByItemIdWithCategory(itemId).getFirst();
        row.setIsApplied(false);
        timeAdjustmentRepository.saveAndFlush(row);

        assertThat(timeAdjustmentRepository.sumPayableMinutesFor(itemId)).isZero();
    }

    @Test
    @DisplayName("an item with no correction reads zero, not null")
    void noCorrectionReadsZero() {
        var scenario = fixture.scenario().build();
        // A null here would propagate into the minutes total and turn "nothing was
        // corrected" into "no minutes at all".
        assertThat(timeAdjustmentRepository.sumPayableMinutesFor(scenario.item().getId())).isZero();
    }
}
