package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftCreateRequest;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Taking a whole shift back.
 *
 * <p>Archived rather than deleted: the work logs on a shift are what somebody
 * was paid for, and a payroll calculated from them has to stay explainable. Only
 * a shift that never held anything may be deleted outright.
 *
 * <p>The part worth protecting is that a withdrawn shift does not go on quietly
 * counting — and that taking one back does not block entering it again, which is
 * what both of the table's constraints would have done before they were made to
 * count live rows only.
 */
@Transactional
class WorkShiftArchiveIT extends AbstractIntegrationTest {

    @Autowired private WorkShiftService workShiftService;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

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

    @Test
    @DisplayName("a withdrawn shift stops counting, and its record stays")
    void archivingKeepsTheRecord() {
        Setup s = setUp();
        Long shiftId = createShift(s, "2026-09-02");

        workShiftService.archive(shiftId, "greškom uneta");
        entityManager.flush();
        entityManager.clear();

        var shift = workShiftRepository.findById(shiftId).orElseThrow();
        assertThat(shift.getArchivedAt()).isNotNull();
        // Kept in step, because several queries already filter on it and knew
        // nothing about archiving.
        assertThat(shift.getIsActive()).isFalse();
        // The reason travels with the shift; there is no column for one.
        assertThat(shift.getNote()).contains("greškom uneta");
    }

    /*
     * The point of making both constraints partial. Withdrawing a shift and
     * entering it again is the ordinary correction, and before this the archived
     * row went on holding both the day and the hours — so a mistake blocked its
     * own fix.
     */
    @Test
    @DisplayName("the same day can be entered again once the wrong shift is withdrawn")
    void aWithdrawnShiftDoesNotBlockItsOwnCorrection() {
        Setup s = setUp();
        Long first = createShift(s, "2026-09-02");
        workShiftService.archive(first, null);
        entityManager.flush();

        Long second = createShift(s, "2026-09-02");

        assertThat(second).isNotEqualTo(first);
        assertThat(workShiftRepository.findById(second).orElseThrow().getArchivedAt()).isNull();
    }

    @Test
    @DisplayName("a withdrawn shift can be put back")
    void restoringWorks() {
        Setup s = setUp();
        Long shiftId = createShift(s, "2026-09-02");

        workShiftService.archive(shiftId, null);
        entityManager.flush();
        workShiftService.restore(shiftId);
        entityManager.flush();
        entityManager.clear();

        var shift = workShiftRepository.findById(shiftId).orElseThrow();
        assertThat(shift.getArchivedAt()).isNull();
        assertThat(shift.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("it cannot be put back into hours another shift has taken")
    void restoringIntoAnOccupiedDayIsRefused() {
        Setup s = setUp();
        Long first = createShift(s, "2026-09-02");
        workShiftService.archive(first, null);
        entityManager.flush();
        createShift(s, "2026-09-02");
        entityManager.flush();

        // Refused in words rather than as a constraint name arriving on screen.
        assertThatThrownBy(() -> workShiftService.restore(first))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("preklapa");
    }

    @Test
    @DisplayName("an empty shift may be deleted outright")
    void anEmptyShiftIsDeletable() {
        Setup s = setUp();
        Long shiftId = createShift(s, "2026-09-02");

        workShiftService.deleteEmpty(shiftId);
        entityManager.flush();
        entityManager.clear();

        assertThat(workShiftRepository.findById(shiftId)).isEmpty();
    }

    @Test
    @DisplayName("a shift with work on it is archived, never deleted")
    void aShiftWithWorkCannotBeDeleted() {
        Setup s = setUp();
        Long shiftId = createShift(s, "2026-09-02");

        // One work log is enough: it is what somebody was paid for. The operation
        // is made here rather than assumed — an INSERT that silently matched no
        // row would leave the shift empty and the test would pass for the wrong
        // reason.
        var category = fixture.scenario().build().workCategory();
        var operation = fixture.operation(category, 10);
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO work_logs (work_shift_id, operation_id, work_code_category_id,
                                       start_at, end_at, is_active, created_at)
                VALUES (:shiftId, :operationId, :categoryId,
                        TIMESTAMPTZ '2026-09-02 06:00+02', TIMESTAMPTZ '2026-09-02 07:00+02',
                        TRUE, now())""")
                .setParameter("shiftId", shiftId)
                .setParameter("operationId", operation.getId())
                .setParameter("categoryId", category.getId())
                .executeUpdate();
        assertThat(inserted).isEqualTo(1);
        entityManager.flush();

        assertThatThrownBy(() -> workShiftService.deleteEmpty(shiftId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Arhivirajte");
    }

    @Test
    @DisplayName("a locked month refuses to have a shift taken out from under it")
    void aLockedMonthIsRefused() {
        Setup s = setUp();
        Long shiftId = createShift(s, "2026-09-02");

        entityManager.createNativeQuery("""
                UPDATE payroll_run_items SET status = 'LOCKED'
                WHERE employee_id = :employeeId
                  AND EXTRACT(YEAR FROM period) = 2026
                  AND EXTRACT(MONTH FROM period) = 9""")
                .setParameter("employeeId", s.employeeId())
                .executeUpdate();
        entityManager.flush();

        assertThatThrownBy(() -> workShiftService.archive(shiftId, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("zaključan");
    }
}
