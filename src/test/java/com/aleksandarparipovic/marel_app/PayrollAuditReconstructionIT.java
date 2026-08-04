package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.AdjustmentPatchDto;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Can somebody's payroll month be reconstructed from the audit trail alone?
 *
 * <p>THE QUESTION THIS ANSWERS, six months later: an employee disputes a payslip.
 * Who changed what, from which value to which, and WHY. Every part of that
 * sentence has to be recoverable, or the trail is decoration.
 *
 * <p>This was owed since Phase 4 and deliberately not written then: the "why" did
 * not exist yet. {@code payroll_adjustments.override_reason} arrived with Phase 6
 * and {@code payroll_time_adjustments.reason} with 2026-08-27-01, so the question
 * is finally answerable and worth asserting.
 *
 * <p>IT IS ALSO THE GUARD ON WHERE THE TRAIL LIVES. The minute correction was
 * audited on payroll_run_items until 2026-09-09-01 moved it to its own table with
 * the column it mirrored; the phone and the bonus moved from item columns to
 * lines. Each of those moves could have quietly dropped a decision out of the
 * record — the tests that watched the old location would simply stop being about
 * anything. This one asks the business question instead of naming a column, so it
 * fails if a decision stops being recorded ANYWHERE.
 */
@Transactional
class PayrollAuditReconstructionIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollAdjustmentRepository adjustmentRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The decisions only: the same trail with the run's own bookkeeping removed.
     *
     * <p>An INSERT into payroll_adjustments is a payroll run being set up — one
     * empty line per category, at zero. Nobody decided anything. An INSERT into
     * payroll_time_adjustments IS a decision, because that row exists only because
     * somebody corrected somebody's time, so it stays.
     */
    private List<String> decisionsFor(Long itemId, Long employeeId) {
        return trailFor(itemId, employeeId).stream()
                .filter(e -> !e.startsWith("payroll_adjustments insert"))
                .toList();
    }

    /** Audit entries on the item row itself. */
    @SuppressWarnings("unchecked")
    private List<String> auditEntriesForItem(Long itemId) {
        entityManager.flush();
        return entityManager.createNativeQuery("""
                SELECT l.changes::text
                FROM audit_logs l
                JOIN audit_tables t ON t.id = l.table_id
                WHERE t.table_name = 'payroll_run_items' AND l.record_id = :itemId
                ORDER BY l.id
                """)
                .setParameter("itemId", itemId)
                .getResultList();
    }

    /**
     * Everything the trail holds about one payroll month, as text.
     *
     * <p>Across FOUR tables, because that is where the decisions ended up: the
     * item, its adjustment lines, its time corrections, and the employee's own
     * payroll values. A reconstruction that had to know which is which would be
     * answering a schema question, not the employee's.
     */
    @SuppressWarnings("unchecked")
    private List<String> trailFor(Long itemId, Long employeeId) {
        entityManager.flush();
        return entityManager.createNativeQuery("""
                SELECT t.table_name || ' ' || a.action_name || ' ' || COALESCE(l.changes::text, '')
                FROM audit_logs l
                JOIN audit_tables t  ON t.id = l.table_id
                JOIN audit_actions a ON a.id = l.action_id
                WHERE (t.table_name = 'payroll_run_items'    AND l.record_id = :itemId)
                   OR (t.table_name = 'payroll_adjustments'  AND l.record_id IN (
                           SELECT p.id FROM payroll_adjustments p WHERE p.payroll_run_item_id = :itemId))
                   OR (t.table_name = 'payroll_time_adjustments' AND l.record_id IN (
                           SELECT p.id FROM payroll_time_adjustments p WHERE p.payroll_run_item_id = :itemId))
                   OR (t.table_name = 'employee_payroll_value_history' AND l.record_id IN (
                           SELECT h.id FROM employee_payroll_value_history h WHERE h.employee_id = :employeeId))
                ORDER BY l.id
                """)
                .setParameter("itemId", itemId)
                .setParameter("employeeId", employeeId)
                .getResultList();
    }

    private void patchLine(Long itemId, String code, Consumer<AdjustmentPatchDto> fill) {
        AdjustmentPatchDto dto = new AdjustmentPatchDto();
        dto.setId(adjustmentRepository.findByItemIdAndCategoryCode(itemId, code).orElseThrow().getId());
        fill.accept(dto);
        PayrollRunItemPatchRequest request = new PayrollRunItemPatchRequest();
        request.setAdjustments(List.of(dto));
        payrollRunItemService.patch(itemId, request);
    }

    @Test
    @DisplayName("every human decision on a payroll month is recoverable from the trail")
    void aMonthCanBeReconstructed() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        Long itemId = scenario.item().getId();
        Long employeeId = scenario.employee().getId();
        payrollRunItemService.getForPayrollAccess(itemId);

        // NOT asserted empty, and that is the defect pinned in
        // recalculationChurnIsNotFilteredOnTheLines: the first calculation of the
        // lines already writes to the trail. Everything below asks what an entry
        // SAYS rather than how many there are, so the churn cannot make it pass.
        int beforeAnybodyDecidedAnything = decisionsFor(itemId, employeeId).size();

        // ── Four decisions, of four different kinds ──────────────────────────
        // Each used to be stored somewhere else, and each is asserted by what it
        // MEANS rather than by the column it lands in.

        // 1. the rate, set by hand for this month
        PayrollRunItemPatchRequest rate = new PayrollRunItemPatchRequest();
        rate.setHourlyRate(new BigDecimal("500.00"));
        payrollRunItemService.patch(itemId, rate);

        // 2. the meal price — an INPUT, the formula still runs
        patchLine(itemId, "MEAL_ALLOWANCE", d -> d.setUnitAmount(new BigDecimal("430.00")));

        // 3. a transport total the calculation did not produce, which by D7 has to
        //    say why
        patchLine(itemId, "TRANSPORT_ALLOWANCE", d -> {
            d.setAmount(new BigDecimal("1000.00"));
            d.setOverrideReason("Dogovoreno sa direktorom, avgust");
        });

        // 4. a correction to paid time, which by 2026-08-27-01 has to say why
        payrollRunItemService.patch(itemId, objectMapper.convertValue(Map.of(
                        "manualAdjustedMinutes", 120,
                        "manualAdjustedMinutesReason", "Zaboravljena smena 12.09."),
                PayrollRunItemPatchRequest.class));

        List<String> trail = decisionsFor(itemId, employeeId);
        assertThat(trail).hasSizeGreaterThan(beforeAnybodyDecidedAnything);

        // ── WHAT: every one of the four is in the record ─────────────────────
        assertThat(trail).anySatisfy(e -> assertThat(e).contains("hourly_rate_overridden"));
        assertThat(trail).anySatisfy(e -> assertThat(e).contains("unit_amount").contains("430"));
        assertThat(trail).anySatisfy(e -> assertThat(e).contains("amount").contains("1000"));
        assertThat(trail).anySatisfy(e -> assertThat(e).contains("minutes").contains("120"));

        // ── FROM WHICH VALUE TO WHICH: old beside new, not just the result ───
        assertThat(trail)
                .as("a trail that records only the new value cannot answer what was changed")
                .anySatisfy(e -> assertThat(e).contains("\"old\"").contains("\"new\""));

        // ── WHY: the two decisions the rules require a reason for carry it ───
        assertThat(trail).anySatisfy(e -> assertThat(e).contains("Dogovoreno sa direktorom"));
        assertThat(trail).anySatisfy(e -> assertThat(e).contains("Zaboravljena smena"));
    }

    @Test
    @DisplayName("a recalculation records nothing on the ITEM — and, today, plenty on its lines")
    void recalculationChurnIsNotFilteredOnTheLines() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        Long employeeId = scenario.employee().getId();
        payrollRunItemService.getForPayrollAccess(itemId);

        int before = decisionsFor(itemId, employeeId).size();

        entityManager.createNativeQuery(
                "UPDATE payroll_run_items SET needs_recalculation = TRUE WHERE id = :id")
                .setParameter("id", itemId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        // A read of a stale item is a write: getForPayrollAccess rewrites the item,
        // every line's system figures, calculated_at and calculation_inputs.
        payrollRunItemService.getForPayrollAccess(itemId);

        // THE ITEM IS CLEAN, and that is 2026-08-26-01 working: its trigger has a
        // WHEN clause naming the one value a person enters.
        assertThat(auditEntriesForItem(itemId))
                .as("the partial trigger keeps recalculation churn off payroll_run_items")
                .isEmpty();

        // THE LINES ARE NOT, AND THIS IS A KNOWN DEFECT — pinned here rather than
        // asserted away, so that fixing it fails this test on purpose.
        //
        // trg_audit_logs_payroll_adjustments is a plain AFTER INSERT OR UPDATE OR
        // DELETE with no WHEN clause, so every recalculation writes a full-row diff
        // per line. In the development database that is 20 954 of 33 472 update
        // entries touching nothing but system_*, calculated_at and
        // calculation_inputs — against roughly thirty real decisions. A dispute six
        // months from now means finding those thirty in thirty thousand.
        //
        // IT CANNOT BE FIXED WITH A WHEN CLAUSE, for the reason 2026-09-03-01 gives
        // about activity: a patch and the recalculation it triggers land in the
        // SAME update on the same row, so no column test separates them — and a
        // clause narrow enough to drop the churn would drop the decision with it.
        // The fix is the one that worked for activity: record decisions explicitly
        // at the caller, and stop auditing the row.
        assertThat(decisionsFor(itemId, employeeId))
                .as("today the calculation's own work lands in the trail; see the comment above")
                .hasSizeGreaterThan(before);
    }

    @Test
    @DisplayName("withdrawing a decision is itself a decision, and is recorded")
    void undoingAnOverrideIsRecordedToo() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        Long employeeId = scenario.employee().getId();
        payrollRunItemService.getForPayrollAccess(itemId);

        patchLine(itemId, "TRANSPORT_ALLOWANCE", d -> {
            d.setAmount(new BigDecimal("1000.00"));
            d.setOverrideReason("Dogovoreno");
        });
        int afterOverride = decisionsFor(itemId, employeeId).size();

        // Back to what the rules say. The figure returns to the system's, and the
        // question "who took the 1.000 off again" has to be answerable too — an
        // audit that records only the giving is half a record.
        patchLine(itemId, "TRANSPORT_ALLOWANCE", d -> d.setClearOverride(true));

        assertThat(decisionsFor(itemId, employeeId)).hasSizeGreaterThan(afterOverride);
    }
}
