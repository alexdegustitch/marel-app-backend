package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeaveService;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeaveRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A full-day leave, run through the REAL daily recalculation.
 *
 * <p>What is proven here is the part that could not be reasoned about safely: a
 * leave shift now carries a display log spanning it (so the karton shows the
 * operation), and that log must be DROPPED by the recalc — priced once through
 * the absence record, never twice, and never counted as covered minutes that
 * would earn phantom overtime. The split by category is asserted from the
 * daily report the worker's calendar and the payroll both read.
 *
 * <p>2026-06-01 is a Monday.
 */
@Transactional
class LeaveShiftRecalcIT extends AbstractIntegrationTest {

    @Autowired private EmployeeLeaveService leaveService;
    @Autowired private WorkShiftRepository workShiftRepository;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private record Setup(Long employeeId, Long goId, Long bId, Long b30Id) {}

    private Setup setUp() {
        var scenario = fixture.scenario().build();
        entityManager.createNativeQuery("""
                INSERT INTO shifts (shift_code, name, start_time, end_time, is_active)
                SELECT 'S1','Prva smena','06:00'::time,'14:00'::time, TRUE
                WHERE NOT EXISTS (SELECT 1 FROM shifts x WHERE x.shift_code = 'S1')""")
                .executeUpdate();
        insertFullDayCategory("GO", "godišnji odmor", "ABSENCE", 1.0, true, null);
        insertFullDayCategory("B", "bolovanje", "SICK_LEAVE", 0.6, true, "STANDARD");
        insertFullDayCategory("B30", "bolovanje preko 30 dana", "SICK_LEAVE", 0.0, false, "EXTENDED");
        entityManager.flush();
        return new Setup(scenario.employee().getId(), categoryId("GO"), categoryId("B"), categoryId("B30"));
    }

    private void insertFullDayCategory(String no, String name, String type, double mult,
                                       boolean paid, String kind) {
        entityManager.createNativeQuery("""
                INSERT INTO work_code_categories
                    (category_no, category_name, type, norm_multiplier, is_paid, sick_leave_kind, is_full_day, valid_from)
                SELECT :no,:name,:type,:mult,:paid,:kind,TRUE, DATE '2020-01-01'
                WHERE NOT EXISTS (SELECT 1 FROM work_code_categories x WHERE x.category_no = :no)""")
                .setParameter("no", no).setParameter("name", name).setParameter("type", type)
                .setParameter("mult", mult).setParameter("paid", paid).setParameter("kind", kind)
                .executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO products (product_name, product_code, description)
                SELECT :name,:no,'IT technical product'
                WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.product_code = :no)""")
                .setParameter("name", name).setParameter("no", no).executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO operations (product_id, op_name, work_code_category_id, norm_required, is_active, description)
                SELECT p.id,:name,c.id,false,true,'IT full-day operation'
                FROM products p, work_code_categories c
                WHERE p.product_code = :no AND c.category_no = :no
                  AND NOT EXISTS (SELECT 1 FROM operations o JOIN work_code_categories wc ON wc.id = o.work_code_category_id
                                  WHERE wc.category_no = :no AND o.is_active AND o.archived_at IS NULL)""")
                .setParameter("name", name).setParameter("no", no).executeUpdate();
    }

    private Long categoryId(String no) {
        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM work_code_categories WHERE category_no = :no")
                .setParameter("no", no).getSingleResult()).longValue();
    }

    /** Enter one leave day, recalculate it, and return its rebuilt daily report. */
    private DailyReport enterAndRecalc(Setup s, Long categoryId, String date) {
        leaveService.apply(new LeaveRequest(
                s.employeeId(), categoryId, LocalDate.parse(date), LocalDate.parse(date),
                null, false, true, false));
        entityManager.flush();
        WorkShift shift = workShiftRepository.findActiveWithCategoryInRange(
                s.employeeId(), LocalDate.parse(date), LocalDate.parse(date)).get(0);
        fixture.recalculate(shift);
        entityManager.flush();
        entityManager.clear();
        return dailyReportRepository.findByWorkShiftId(shift.getId()).orElseThrow();
    }

    private long categoryRowCount(Long workShiftId) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM daily_report_categories drc
                JOIN daily_reports dr ON dr.id = drc.daily_report_id
                WHERE dr.work_shift_id = :id""")
                .setParameter("id", workShiftId).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("godišnji odmor prices as a paid absence, once, with no phantom overtime")
    void vacationPricesOnce() {
        Setup s = setUp();
        DailyReport report = enterAndRecalc(s, s.goId(), "2026-06-01");

        assertThat(report.getTotalAbsencePaidMinutes()).isEqualTo(480);
        assertThat(report.getTotalSickLeavePaidMinutes()).isZero();
        // The display log was DROPPED: it produced no covered minutes and no
        // second category row. One row prices the day, not two.
        assertThat(categoryRowCount(report.getWorkShift().getId())).isEqualTo(1);
        assertThat(report.getTotalWorkMinutes()).isZero();

        // No overtime earned on a day nobody worked.
        long overtime = ((Number) entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(overtime_minutes),0) FROM overtime_records WHERE employee_id = :id")
                .setParameter("id", s.employeeId()).getSingleResult()).longValue();
        assertThat(overtime).isZero();
    }

    @Test
    @DisplayName("ordinary bolovanje prices as paid sick leave")
    void sickLeavePricesAsPaid() {
        Setup s = setUp();
        DailyReport report = enterAndRecalc(s, s.bId(), "2026-06-01");

        assertThat(report.getTotalSickLeavePaidMinutes()).isEqualTo(480);
        assertThat(report.getTotalAbsencePaidMinutes()).isZero();
        assertThat(categoryRowCount(report.getWorkShift().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("bolovanje preko 30 dana prices as unpaid sick leave")
    void extendedSickLeavePricesAsUnpaid() {
        Setup s = setUp();
        DailyReport report = enterAndRecalc(s, s.b30Id(), "2026-06-01");

        assertThat(report.getTotalSickLeaveUnpaidMinutes()).isEqualTo(480);
        assertThat(report.getTotalSickLeavePaidMinutes()).isZero();
    }

    @Test
    @DisplayName("the leave shift carries the operation that draws it in the karton")
    void theShiftShowsItsOperation() {
        Setup s = setUp();
        DailyReport report = enterAndRecalc(s, s.goId(), "2026-06-01");
        long logs = ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM work_logs wl
                JOIN work_code_categories c ON c.id = wl.work_code_category_id
                WHERE wl.work_shift_id = :id AND c.category_no = 'GO' AND wl.is_active""")
                .setParameter("id", report.getWorkShift().getId()).getSingleResult()).longValue();
        assertThat(logs).isEqualTo(1);
    }
}
