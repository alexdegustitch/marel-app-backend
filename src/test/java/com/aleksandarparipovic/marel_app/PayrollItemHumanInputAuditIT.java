package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService;
import com.aleksandarparipovic.marel_app.payroll_run_item.dto.PayrollRunItemPatchRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person TYPES on a payroll item leaves a trail; what the calculation
 * derives does not.
 *
 * <p>2026-08-05-02 left payroll_run_items unaudited on the grounds that "the
 * amounts on an item are all derived from the adjustments and categories that ARE
 * audited". True of the money, false of the manual time correction and
 * {@code hourly_rate_overridden}: nobody derives those, an administrator enters
 * them, and they change what an employee is paid. Until 2026-08-26-01 they were
 * recorded nowhere.
 *
 * <p>THE MINUTES MOVED TABLES. manual_adjusted_minutes was dropped in
 * 2026-09-09-01 and the correction is now rows in payroll_time_adjustments, which
 * carry their own audit trigger — and their own reason, which the item column
 * never had. So the assertion moved with it rather than being deleted: typing a
 * correction must still be recorded, just where the correction now lives.
 *
 * <p>The other half of the requirement is just as important and is why the
 * trigger carries a WHEN clause rather than auditing the row: a recalculation
 * rewrites almost every column on the item and must produce NO audit entry, or
 * the human decisions drown in system churn.
 */
@Transactional
class PayrollItemHumanInputAuditIT extends AbstractIntegrationTest {

    @Autowired private PayrollRunItemService payrollRunItemService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The DTO exposes no setter for this field; the controller reaches it through Jackson. */
    private PayrollRunItemPatchRequest patchWithMinutes(int minutes) {
        // The reason is compulsory since 2026-08-27-01: a correction to somebody's
        // paid time that says nothing about why is what that table exists to stop.
        return objectMapper.convertValue(Map.of(
                        "manualAdjustedMinutes", minutes,
                        "manualAdjustedMinutesReason", "Zaboravljena smena 12.09."),
                PayrollRunItemPatchRequest.class);
    }

    /** Audit entries for the item's time-correction ROWS, wherever they were written. */
    @SuppressWarnings("unchecked")
    private List<String> timeCorrectionAuditFor(Long itemId) {
        entityManager.flush();
        return entityManager.createNativeQuery("""
                SELECT l.changes::text
                FROM audit_logs l
                JOIN audit_tables t ON t.id = l.table_id
                WHERE t.table_name = 'payroll_time_adjustments'
                  AND l.record_id IN (SELECT a.id FROM payroll_time_adjustments a
                                      WHERE a.payroll_run_item_id = :itemId)
                ORDER BY l.id
                """)
                .setParameter("itemId", itemId)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<String> auditEntriesFor(Long itemId) {
        entityManager.flush();
        return entityManager.createNativeQuery("""
                SELECT l.changes::text
                FROM audit_logs l
                JOIN audit_tables t ON t.id = l.table_id
                WHERE t.table_name = 'payroll_run_items'
                  AND l.record_id = :itemId
                ORDER BY l.id
                """)
                .setParameter("itemId", itemId)
                .getResultList();
    }

    // ── what must be recorded ───────────────────────────────────────────────

    @Test
    @DisplayName("typing manual minutes is recorded, with the reason it was given")
    void manualMinutesAreRecorded() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();
        assertThat(timeCorrectionAuditFor(itemId)).isEmpty();

        payrollRunItemService.patch(itemId, patchWithMinutes(120));

        // On payroll_time_adjustments now, and richer for it: the item column could
        // record that 120 minutes appeared, never why.
        assertThat(timeCorrectionAuditFor(itemId))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("minutes")
                .contains("120")
                .contains("Zaboravljena smena");

        // And NOT on the item: it holds no correction any more, so a trail there
        // would be about a column that no longer exists.
        assertThat(auditEntriesFor(itemId)).isEmpty();
    }

    @Test
    @DisplayName("overriding the hourly rate is recorded")
    void overridingTheRateIsRecorded() {
        var scenario = fixture.scenario().hourlyRate("420.00").build();
        Long itemId = scenario.item().getId();

        PayrollRunItemPatchRequest patch = new PayrollRunItemPatchRequest();
        patch.setHourlyRate(new BigDecimal("500.00"));
        payrollRunItemService.patch(itemId, patch);

        assertThat(auditEntriesFor(itemId))
                .isNotEmpty()
                .anySatisfy(changes -> assertThat(changes).contains("hourly_rate_overridden"));
    }

    // ── what must NOT be recorded ───────────────────────────────────────────

    @Test
    @DisplayName("a recalculation writes most of the row and records nothing")
    void aRecalculationIsNotRecorded() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        PayrollRunItem stale = payrollRunItemService.findById(itemId);
        stale.setNeedsRecalculation(true);
        entityManager.flush();

        // Rewrites the amounts, the minutes totals, the earnings — everything the
        // calculation owns. None of it is somebody's decision.
        payrollRunItemService.getForPayrollAccess(itemId);

        assertThat(auditEntriesFor(itemId)).isEmpty();
        assertThat(timeCorrectionAuditFor(itemId)).isEmpty();
    }

    @Test
    @DisplayName("entering the same minutes again records nothing")
    void anUnchangedValueIsNotRecorded() {
        var scenario = fixture.scenario().build();
        Long itemId = scenario.item().getId();

        payrollRunItemService.patch(itemId, patchWithMinutes(120));
        int afterFirst = timeCorrectionAuditFor(itemId).size();

        payrollRunItemService.patch(itemId, patchWithMinutes(120));

        // Nothing is written when nothing changed. Re-saving used to move
        // edited_by and edited_at, which is a row change, which is an audit entry
        // — so re-opening a payroll and pressing save would have read as an edit
        // to somebody's paid time.
        assertThat(timeCorrectionAuditFor(itemId)).hasSize(afterFirst);
        assertThat(auditEntriesFor(itemId)).isEmpty();
    }
}
