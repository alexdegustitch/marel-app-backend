package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordArchiveService;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftCreateRequest;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Taking a whole karton back — one worker's month — and putting it back.
 *
 * <p>Why it needed a test of its own rather than resting on the shift's: a
 * karton withdrawal is a CASCADE, and a cascade has two ways to be quietly
 * wrong. It can hide the karton and leave the shifts under it still counting,
 * so the month's totals never move and nobody can see why; or it can bring the
 * karton back and leave the shifts withdrawn, so a restore returns an empty
 * month. Both look fine from the list. These pin both directions.
 *
 * <p>The third thing worth protecting is that entering a shift cannot resurrect
 * work under a withdrawn karton — {@code getOrCreateMonthlyRecord} finds the
 * archived row by (employee, month) and would happily hang a new shift off it.
 */
@Transactional
class EmployeeRecordArchiveIT extends AbstractIntegrationTest {

    @Autowired private EmployeeRecordArchiveService archiveService;
    @Autowired private EmployeeRecordService recordService;
    @Autowired private EmployeeRecordRepository recordRepository;
    @Autowired private WorkShiftService workShiftService;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private static final int YEAR = 2026;
    private static final int MONTH = 9;

    private record Setup(Long employeeId, Long supervisorId, Long categoryId, Long shiftId) {}

    @SuppressWarnings("unchecked")
    private Setup setUp() {
        var scenario = fixture.scenario().build();
        Long supervisorId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM users ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
        entityManager.createNativeQuery("""
                INSERT INTO shifts (shift_code, name, start_time, end_time, is_active)
                SELECT 'S1','Prva smena','06:00'::time,'14:00'::time, TRUE
                WHERE NOT EXISTS (SELECT 1 FROM shifts x WHERE x.shift_code = 'S1')""")
                .executeUpdate();
        entityManager.flush();

        List<Object[]> shifts = entityManager
                .createNativeQuery("SELECT id, start_time FROM shifts ORDER BY start_time")
                .getResultList();
        return new Setup(scenario.employee().getId(), supervisorId,
                scenario.workCategory().getId(), ((Number) shifts.getFirst()[0]).longValue());
    }

    private Long createShift(Setup s, String workDate) {
        WorkShiftCreateRequest req = new WorkShiftCreateRequest();
        req.setEmployeeId(s.employeeId());
        req.setWorkDate(workDate);
        req.setShiftType(s.shiftId());
        req.setWorkCategoryCodeId(s.categoryId());
        req.setSupervisorId(s.supervisorId());
        return workShiftService.createShift(req).id();
    }

    private Long kartonOf(Setup s) {
        return recordService.findRecordIdForEmployeeAndMonth(s.employeeId(), YEAR, MONTH).orElseThrow();
    }

    private void closeThePayroll(Setup s, String status) {
        entityManager.createNativeQuery("""
                UPDATE payroll_run_items SET status = :status
                WHERE employee_id = :employeeId
                  AND EXTRACT(YEAR FROM period) = :year
                  AND EXTRACT(MONTH FROM period) = :month""")
                .setParameter("status", status)
                .setParameter("employeeId", s.employeeId())
                .setParameter("year", YEAR)
                .setParameter("month", MONTH)
                .executeUpdate();
        entityManager.flush();
    }

    // ── Withdrawing ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a withdrawn karton is stamped and takes its shifts down with it")
    void archivingCascadesIntoTheShifts() {
        Setup s = setUp();
        Long first = createShift(s, "2026-09-02");
        Long second = createShift(s, "2026-09-03");
        Long kartonId = kartonOf(s);

        var summary = archiveService.archive(kartonId, "mesec otvoren greškom");
        entityManager.flush();
        entityManager.clear();

        assertThat(summary.kartoni()).isEqualTo(1);
        assertThat(summary.shifts()).isEqualTo(2);

        var karton = recordRepository.findById(kartonId).orElseThrow();
        assertThat(karton.getArchivedAt()).isNotNull();
        // archived_by is stamped from the authenticated caller, which an
        // integration test has none of; the reason is the part written from the
        // request and therefore the part worth asserting here.
        assertThat(karton.getArchiveReason()).isEqualTo("mesec otvoren greškom");
        // Kept in step, because queries written before archiving existed read it.
        assertThat(karton.getActive()).isFalse();

        /*
         * The half that would be invisible from the list: hiding the karton
         * while its shifts stay live would leave the month's hours in every
         * total that reads work_shifts by employee_id rather than by karton.
         */
        assertThat(workShiftRepository.findById(first).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(workShiftRepository.findById(second).orElseThrow().getArchivedAt()).isNotNull();
    }

    @Test
    @DisplayName("the month list stops showing it, and the archived view shows it")
    void theListsSplitLiveFromWithdrawn() {
        Setup s = setUp();
        createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);

        var live = recordService.getEmployeeRecordsByYearAndMonth(
                YEAR, MONTH, null, false, PageRequest.of(0, 200));
        assertThat(live.getContent()).extracting("id").contains(kartonId);

        archiveService.archive(kartonId, null);
        entityManager.flush();
        entityManager.clear();

        assertThat(recordService.getEmployeeRecordsByYearAndMonth(
                        YEAR, MONTH, null, false, PageRequest.of(0, 200)).getContent())
                .extracting("id").doesNotContain(kartonId);

        // Reachable, or there would be no way to put it back.
        assertThat(recordService.getEmployeeRecordsByYearAndMonth(
                        YEAR, MONTH, null, true, PageRequest.of(0, 200)).getContent())
                .extracting("id").contains(kartonId);
    }

    @Test
    @DisplayName("withdrawing one twice is refused")
    void archivingTwiceIsRefused() {
        Setup s = setUp();
        Long kartonId = kartonOf(s);
        archiveService.archive(kartonId, null);
        entityManager.flush();

        assertThatThrownBy(() -> archiveService.archive(kartonId, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("već arhiviran");
    }

    // ── Putting it back ──────────────────────────────────────────────────────

    @Test
    @DisplayName("restoring brings back the shifts that went down with it")
    void restoringBringsTheCascadeBack() {
        Setup s = setUp();
        Long shiftId = createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);

        archiveService.archive(kartonId, null);
        entityManager.flush();
        var summary = archiveService.restore(kartonId);
        entityManager.flush();
        entityManager.clear();

        assertThat(summary.shifts()).isEqualTo(1);

        var karton = recordRepository.findById(kartonId).orElseThrow();
        assertThat(karton.getArchivedAt()).isNull();
        assertThat(karton.getArchiveReason()).isNull();
        assertThat(karton.getActive()).isTrue();
        // The other half of the cascade: a restore that returned an empty month
        // would look identical on the list.
        assertThat(workShiftRepository.findById(shiftId).orElseThrow().getArchivedAt()).isNull();
    }

    @Test
    @DisplayName("a shift withdrawn earlier, on its own, stays withdrawn")
    void anEarlierShiftWithdrawalIsItsOwnDecision() {
        Setup s = setUp();
        Long withdrawnEarlier = createShift(s, "2026-09-02");
        Long live = createShift(s, "2026-09-03");
        Long kartonId = kartonOf(s);

        // Somebody took this one day back deliberately, before the karton went.
        workShiftService.archive(withdrawnEarlier, "pogrešan dan");
        entityManager.flush();

        archiveService.archive(kartonId, null);
        entityManager.flush();
        archiveService.restore(kartonId);
        entityManager.flush();
        entityManager.clear();

        // Restoring the karton must not undo a decision nobody asked it to undo.
        assertThat(workShiftRepository.findById(withdrawnEarlier).orElseThrow().getArchivedAt()).isNotNull();
        assertThat(workShiftRepository.findById(live).orElseThrow().getArchivedAt()).isNull();
    }

    // ── Entering work into a withdrawn month ─────────────────────────────────

    @Test
    @DisplayName("a withdrawn karton refuses to take new shifts")
    void aWithdrawnKartonRefusesNewWork() {
        Setup s = setUp();
        Long kartonId = kartonOf(s);
        archiveService.archive(kartonId, null);
        entityManager.flush();

        /*
         * The quiet failure this prevents: getOrCreateMonthlyRecord finds the
         * archived karton by (employee, month) and hangs the shift off it, so
         * the work exists, is counted by the recalculation, and lives under a
         * month no list will show.
         */
        assertThatThrownBy(() -> createShift(s, "2026-09-04"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("arhiviran");
    }

    // ── Deleting, and refusing to ────────────────────────────────────────────

    @Test
    @DisplayName("an empty karton may be deleted outright")
    void anEmptyKartonIsDeletable() {
        Setup s = setUp();
        /*
         * A month the fixture never touched. The scenario's own month already
         * carries a monthly report, which is exactly what must BLOCK a delete —
         * so deleting is tested on a karton that really holds nothing: opened,
         * then found to be a mistake before anybody entered a day.
         */
        Long kartonId = recordService
                .getOrCreateMonthlyRecord(s.employeeId(), LocalDate.of(2027, 3, 1))
                .getId();
        entityManager.flush();

        assertThat(archiveService.preview(kartonId).deletable()).isTrue();

        archiveService.deleteEmpty(kartonId);
        entityManager.flush();
        entityManager.clear();

        assertThat(recordRepository.findById(kartonId)).isEmpty();
    }

    @Test
    @DisplayName("a karton with a shift on it is archived, never deleted")
    void aKartonWithWorkCannotBeDeleted() {
        Setup s = setUp();
        createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);

        assertThat(archiveService.preview(kartonId).deletable()).isFalse();
        assertThatThrownBy(() -> archiveService.deleteEmpty(kartonId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Arhivirajte");
    }

    @Test
    @DisplayName("a withdrawn shift still counts as history, so the karton stays")
    void anArchivedShiftStillBlocksTheDelete() {
        Setup s = setUp();
        Long shiftId = createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);

        workShiftService.archive(shiftId, null);
        entityManager.flush();

        // A withdrawal is itself a record of something that happened.
        var preview = archiveService.preview(kartonId);
        assertThat(preview.shifts()).isZero();
        assertThat(preview.archivedShifts()).isEqualTo(1);
        assertThat(preview.deletable()).isFalse();
    }

    // ── The month as the guard ───────────────────────────────────────────────

    @Test
    @DisplayName("a locked month refuses to have its karton taken away")
    void aLockedMonthIsRefused() {
        Setup s = setUp();
        createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);
        closeThePayroll(s, "LOCKED");

        assertThatThrownBy(() -> archiveService.archive(kartonId, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("zaključan");
    }

    @Test
    @DisplayName("a month already handed to payroll refuses it too")
    void anApprovedMonthIsRefused() {
        Setup s = setUp();
        createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);
        closeThePayroll(s, "APPROVED");

        assertThatThrownBy(() -> archiveService.archive(kartonId, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("predat ili zaključan");
    }

    @Test
    @DisplayName("the preview says why, before anybody clicks")
    void thePreviewCarriesTheRefusal() {
        Setup s = setUp();
        createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);
        closeThePayroll(s, "SENT");

        var preview = archiveService.preview(kartonId);
        assertThat(preview.blockedReason()).contains("predat ili zaključan");
        // It holds a day of work, so the withdrawal would have to be signed.
        assertThat(preview.requiresPassword()).isTrue();
        assertThat(preview.shifts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty karton needs no signature; one with work does")
    void theSignatureFollowsTheWork() {
        Setup s = setUp();
        Long kartonId = kartonOf(s);

        assertThat(archiveService.preview(kartonId).requiresPassword()).isFalse();

        createShift(s, "2026-09-02");
        entityManager.flush();

        assertThat(archiveService.preview(kartonId).requiresPassword()).isTrue();
    }

    // ── The whole month at once ──────────────────────────────────────────────

    @Test
    @DisplayName("a month opened by mistake is withdrawn whole")
    void theWholeMonthCanBeWithdrawn() {
        Setup s = setUp();
        createShift(s, "2026-09-02");
        Long kartonId = kartonOf(s);

        var preview = archiveService.previewMonth(YEAR, MONTH);
        assertThat(preview.kartoni()).isPositive();
        assertThat(preview.shifts()).isPositive();
        assertThat(preview.blocked()).isZero();

        var summary = archiveService.archiveMonth(YEAR, MONTH, "mesec otvoren dva puta");
        entityManager.flush();
        entityManager.clear();

        assertThat(summary.kartoni()).isEqualTo(preview.kartoni());
        assertThat(recordRepository.findById(kartonId).orElseThrow().getArchivedAt()).isNotNull();
        // Nothing left to take: the second call has no live karton to act on.
        assertThat(archiveService.previewMonth(YEAR, MONTH).kartoni()).isZero();
    }

    @Test
    @DisplayName("one closed payroll refuses the whole month, not half of it")
    void aClosedPayrollRefusesTheWholeMonth() {
        Setup s = setUp();
        createShift(s, "2026-09-02");
        // A second worker's karton in the same month, whose payroll is open.
        var other = fixture.scenario().build();
        Long otherKarton = recordService
                .findRecordIdForEmployeeAndMonth(other.employee().getId(), YEAR, MONTH)
                .orElseThrow();
        closeThePayroll(s, "LOCKED");

        assertThat(archiveService.previewMonth(YEAR, MONTH).blocked()).isEqualTo(1);
        assertThatThrownBy(() -> archiveService.archiveMonth(YEAR, MONTH, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ne mogu arhivirati");
        entityManager.clear();

        // Half-done is the one outcome nobody asked for: the open karton stands.
        assertThat(recordRepository.findById(otherKarton).orElseThrow().getArchivedAt()).isNull();
    }
}
