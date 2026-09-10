package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee_calendar.EmployeeCalendarService;
import com.aleksandarparipovic.marel_app.employee_calendar.dto.EmployeeCalendarDtos.CalendarDay;
import com.aleksandarparipovic.marel_app.employee_calendar.dto.EmployeeCalendarDtos.EmployeeCalendarResponse;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeaveService;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeaveRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.dto.WorkShiftCreateRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's calendar month, read back as one answer.
 *
 * <p>Protected: withdrawn shifts are absent, an entered leave day carries its
 * category and kind, the absence-days strip counts by category, and the meal
 * figure is the EFFECTIVE one — computed plus the hand correction — because
 * that is the number the month pays.
 */
@Transactional
class EmployeeCalendarIT extends AbstractIntegrationTest {

    @Autowired private EmployeeCalendarService calendarService;
    @Autowired private EmployeeLeaveService leaveService;
    @Autowired private WorkShiftService workShiftService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("a month answers with its shifts, its leave days and effective meals")
    void monthReadsBackWhatWasWritten() {
        var scenario = fixture.scenario().build();
        Long employeeId = scenario.employee().getId();
        Long supervisorId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM users ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
        entityManager.createNativeQuery("""
                INSERT INTO shifts (shift_code, name, start_time, end_time, is_active)
                SELECT 'S1','Prva smena','06:00'::time,'14:00'::time, TRUE
                WHERE NOT EXISTS (SELECT 1 FROM shifts x WHERE x.shift_code = 'S1')""")
                .executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO work_code_categories
                    (category_no, category_name, type, norm_multiplier, is_paid, sick_leave_kind, is_full_day, valid_from)
                SELECT 'B','bolovanje','SICK_LEAVE',0.6,TRUE,'STANDARD',TRUE, DATE '2020-01-01'
                WHERE NOT EXISTS (SELECT 1 FROM work_code_categories x WHERE x.category_no = 'B')""")
                .executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO products (product_name, product_code, description)
                SELECT 'bolovanje','B','IT technical product'
                WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.product_code = 'B')""")
                .executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO operations (product_id, op_name, work_code_category_id, norm_required, is_active, description)
                SELECT p.id, 'bolovanje', c.id, false, true, 'IT full-day operation'
                FROM products p, work_code_categories c
                WHERE p.product_code = 'B' AND c.category_no = 'B'
                  AND NOT EXISTS (SELECT 1 FROM operations o JOIN work_code_categories wc ON wc.id = o.work_code_category_id
                                  WHERE wc.category_no = 'B' AND o.is_active AND o.archived_at IS NULL)""")
                .executeUpdate();
        entityManager.flush();
        Long shiftTemplateId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM shifts ORDER BY start_time LIMIT 1")
                .getSingleResult()).longValue();
        Long sickId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM work_code_categories WHERE category_no = 'B'")
                .getSingleResult()).longValue();

        // A worked Monday, a withdrawn Tuesday, and two sick days.
        WorkShiftCreateRequest worked = new WorkShiftCreateRequest();
        worked.setEmployeeId(employeeId);
        worked.setWorkDate("2026-06-01");
        worked.setShiftType(shiftTemplateId);
        worked.setWorkCategoryCodeId(scenario.workCategory().getId());
        worked.setSupervisorId(supervisorId);
        Long workedId = workShiftService.createShift(worked).id();

        WorkShiftCreateRequest withdrawn = new WorkShiftCreateRequest();
        withdrawn.setEmployeeId(employeeId);
        withdrawn.setWorkDate("2026-06-02");
        withdrawn.setShiftType(shiftTemplateId);
        withdrawn.setWorkCategoryCodeId(scenario.workCategory().getId());
        withdrawn.setSupervisorId(supervisorId);
        Long withdrawnId = workShiftService.createShift(withdrawn).id();
        workShiftService.archive(withdrawnId, "test");

        leaveService.apply(new LeaveRequest(
                employeeId, sickId,
                LocalDate.parse("2026-06-03"), LocalDate.parse("2026-06-04"),
                null, false, false));

        // The worked day's report, with a hand-corrected meal on it.
        entityManager.createNativeQuery("""
                INSERT INTO daily_reports (employee_id, work_shift_id, work_date,
                    total_shift_minutes, total_approved_minutes, approved_performance_rate,
                    meals_count, meals_manual_delta, is_meal_allowed, version, calc_version)
                VALUES (:employeeId, :shiftId, DATE '2026-06-01', 480, 500, 104, 1, 1, TRUE, 1, 1)""")
                .setParameter("employeeId", employeeId)
                .setParameter("shiftId", workedId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        EmployeeCalendarResponse month = calendarService.month(employeeId, 2026, 6);

        assertThat(month.days()).hasSize(30);
        CalendarDay monday = month.days().get(0);
        assertThat(monday.shifts()).hasSize(1);
        assertThat(monday.shifts().getFirst().effectiveMealsCount()).isEqualTo(2);
        // The withdrawn Tuesday is nobody's work any more.
        assertThat(month.days().get(1).shifts()).isEmpty();
        // The sick day names its category and its role in the thirty-day rule.
        CalendarDay sickDay = month.days().get(2);
        assertThat(sickDay.shifts().getFirst().categoryNo()).isEqualTo("B");
        assertThat(sickDay.shifts().getFirst().categoryType()).isEqualTo("SICK_LEAVE");
        assertThat(sickDay.shifts().getFirst().sickLeaveKind()).isEqualTo("STANDARD");

        assertThat(month.summary().meals()).isEqualTo(2);
        assertThat(month.summary().workedDays()).isEqualTo(1);
        assertThat(month.summary().absenceDays())
                .anySatisfy(entry -> {
                    assertThat(entry.categoryNo()).isEqualTo("B");
                    assertThat(entry.days()).isEqualTo(2);
                });
        assertThat(month.employeeRecordId()).isNotNull();
        assertThat(month.leavePeriods()).hasSize(1);
    }
}
