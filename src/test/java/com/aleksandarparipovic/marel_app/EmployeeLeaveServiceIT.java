package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeavePeriod;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeavePeriodRepository;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeaveService;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeaveApplyResponse;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeavePreviewResponse;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A leave entered for a whole od–do period.
 *
 * <p>What is protected here is the RULEBOOK the owner stated, not the plumbing:
 * weekdays only; a day already the same type writes nothing; a shift of another
 * type is archived only with consent; after thirty continuous CALENDAR days of
 * ordinary sick leave the days continue as the EXTENDED category, a break
 * restarts the count, and a handed-over month refuses the entry.
 *
 * <p>Dates are chosen against the 2026 calendar: 2026-06-01 is a Monday.
 */
@Transactional
class EmployeeLeaveServiceIT extends AbstractIntegrationTest {

    @Autowired private EmployeeLeaveService leaveService;
    @Autowired private EmployeeLeavePeriodRepository leaveRepository;
    @Autowired private WorkShiftService workShiftService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private record Setup(Long employeeId, Long supervisorId, Long workCategoryId,
                         Long sickId, Long extendedId, Long vacationId) {}

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
        // The three leave categories the live catalogue holds, with their roles
        // in the thirty-day rule declared the way V39 declares them, each with
        // the single full-day operation V40 gives them.
        insertCategory("B", "bolovanje", "SICK_LEAVE", 0.6, true, "STANDARD");
        insertCategory("B30", "bolovanje preko 30 dana", "SICK_LEAVE", 0.0, false, "EXTENDED");
        insertCategory("GO", "godišnji odmor", "ABSENCE", 1.0, true, null);
        entityManager.flush();

        return new Setup(scenario.employee().getId(), supervisorId,
                scenario.workCategory().getId(),
                categoryId("B"), categoryId("B30"), categoryId("GO"));
    }

    private void insertCategory(String no, String name, String type, double multiplier,
                                boolean paid, String kind) {
        entityManager.createNativeQuery("""
                INSERT INTO work_code_categories
                    (category_no, category_name, type, norm_multiplier, is_paid, sick_leave_kind, is_full_day, valid_from)
                SELECT :no, :name, :type, :multiplier, :paid, :kind, true, DATE '2020-01-01'
                WHERE NOT EXISTS (SELECT 1 FROM work_code_categories x WHERE x.category_no = :no)""")
                .setParameter("no", no).setParameter("name", name).setParameter("type", type)
                .setParameter("multiplier", multiplier).setParameter("paid", paid)
                .setParameter("kind", kind)
                .executeUpdate();
        // The single full-day operation the log hangs from — V40 seeds these for
        // GO/B/BP/B30, but only where the category already existed at migration
        // time; a test that creates the category itself must create its operation.
        entityManager.createNativeQuery("""
                INSERT INTO products (product_name, product_code, description)
                SELECT :name, :no, 'IT technical product'
                WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.product_code = :no)""")
                .setParameter("name", name).setParameter("no", no)
                .executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO operations (product_id, op_name, work_code_category_id, norm_required, is_active, description)
                SELECT p.id, :name, c.id, false, true, 'IT full-day operation'
                FROM products p, work_code_categories c
                WHERE p.product_code = :no AND c.category_no = :no
                  AND NOT EXISTS (
                    SELECT 1 FROM operations o JOIN work_code_categories wc ON wc.id = o.work_code_category_id
                    WHERE wc.category_no = :no AND o.is_active AND o.archived_at IS NULL)""")
                .setParameter("name", name).setParameter("no", no)
                .executeUpdate();
    }

    private Long categoryId(String no) {
        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM work_code_categories WHERE category_no = :no")
                .setParameter("no", no).getSingleResult()).longValue();
    }

    private LeaveRequest request(Setup s, Long categoryId, String from, String to,
                                 boolean archiveConflicts, boolean acceptExtended) {
        return new LeaveRequest(s.employeeId(), categoryId,
                LocalDate.parse(from), LocalDate.parse(to), null, archiveConflicts, acceptExtended, false);
    }

    /** category_no of the live shift on one date, or null when the day is empty. */
    private String categoryOn(Setup s, String date) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT wcc.category_no FROM work_shifts ws
                JOIN work_code_categories wcc ON wcc.id = ws.work_code_category_id
                WHERE ws.employee_id = :employeeId AND ws.work_date = CAST(:date AS date)
                  AND ws.archived_at IS NULL""")
                .setParameter("employeeId", s.employeeId())
                .setParameter("date", date)
                .getResultList();
        return rows.isEmpty() ? null : (String) rows.getFirst();
    }

    private long liveShiftCount(Setup s) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM work_shifts
                WHERE employee_id = :employeeId AND archived_at IS NULL""")
                .setParameter("employeeId", s.employeeId()).getSingleResult()).longValue();
    }

    // ── The weekday shape of a period ────────────────────────────────────────

    @Test
    @DisplayName("a period writes weekdays only, each day a shift with its absence record")
    void aPeriodWritesWeekdaysOnly() {
        Setup s = setUp();

        LeaveApplyResponse response = leaveService.apply(
                request(s, s.sickId(), "2026-06-01", "2026-06-14", false, false));
        entityManager.flush();

        // Mon 1 – Fri 5 and Mon 8 – Fri 12; both weekends untouched.
        assertThat(response.createdShifts()).isEqualTo(10);
        assertThat(categoryOn(s, "2026-06-03")).isEqualTo("B");
        assertThat(categoryOn(s, "2026-06-06")).isNull();
        assertThat(categoryOn(s, "2026-06-07")).isNull();

        // The absence record is what the daily recalculation prices — a day
        // without one would show a shift and pay nothing.
        long absences = ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM absence_records ar
                JOIN work_shifts ws ON ws.id = ar.work_shift_id
                WHERE ws.employee_id = :employeeId AND ar.is_active = TRUE""")
                .setParameter("employeeId", s.employeeId()).getSingleResult()).longValue();
        assertThat(absences).isEqualTo(10);

        // Every created day queued for its rebuild, and the month for its sums.
        long dailyJobs = ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM daily_report_recalc_queue WHERE employee_id = :id")
                .setParameter("id", s.employeeId()).getSingleResult()).longValue();
        assertThat(dailyJobs).isEqualTo(10);
        long monthlyJobs = ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM monthly_report_recalc_queue
                WHERE employee_id = :id AND report_year = 2026 AND report_month = 6""")
                .setParameter("id", s.employeeId()).getSingleResult()).longValue();
        assertThat(monthlyJobs).isEqualTo(1);

        // The intent survives as a period row.
        assertThat(leaveRepository.findIntersecting(
                s.employeeId(), LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30")))
                .hasSize(1);
    }

    @Test
    @DisplayName("the quick single-day door may count Saturday as worked; Sunday never")
    void includeSaturdaysWritesSaturdayButNotSunday() {
        Setup s = setUp();

        // 2026-06-06 is a Saturday. The calendar door skips it whole…
        LeavePreviewResponse calendarDoor = leaveService.preview(new LeaveRequest(
                s.employeeId(), s.vacationId(),
                LocalDate.parse("2026-06-06"), LocalDate.parse("2026-06-06"),
                null, false, false, false));
        assertThat(calendarDoor.toCreate()).isZero();
        assertThat(calendarDoor.days().getFirst().status()).isEqualTo("SKIP_WEEKEND");

        // …while the board's quick door writes it like any workday.
        LeaveApplyResponse applied = leaveService.apply(new LeaveRequest(
                s.employeeId(), s.vacationId(),
                LocalDate.parse("2026-06-06"), LocalDate.parse("2026-06-06"),
                null, false, false, true));
        entityManager.flush();
        assertThat(applied.createdShifts()).isEqualTo(1);
        assertThat(categoryOn(s, "2026-06-06")).isEqualTo("GO");

        // Sunday stays a skipped day even for the quick door.
        LeavePreviewResponse sunday = leaveService.preview(new LeaveRequest(
                s.employeeId(), s.vacationId(),
                LocalDate.parse("2026-06-07"), LocalDate.parse("2026-06-07"),
                null, false, false, true));
        assertThat(sunday.toCreate()).isZero();
        assertThat(sunday.days().getFirst().status()).isEqualTo("SKIP_WEEKEND");
    }

    @Test
    @DisplayName("entering the same period again writes nothing")
    void sameTypeIsIdempotent() {
        Setup s = setUp();
        leaveService.apply(request(s, s.sickId(), "2026-06-01", "2026-06-05", false, false));
        entityManager.flush();

        LeaveApplyResponse second = leaveService.apply(
                request(s, s.sickId(), "2026-06-01", "2026-06-05", false, false));

        assertThat(second.createdShifts()).isZero();
        assertThat(liveShiftCount(s)).isEqualTo(5);
    }

    // ── The thirty-day rule ──────────────────────────────────────────────────

    @Test
    @DisplayName("after thirty calendar days the sick leave continues as the extended category")
    void thirtyDaysEscalate() {
        Setup s = setUp();

        // 2026-06-01 .. 2026-07-15: day 30 is 2026-06-30, day 31 is 2026-07-01.
        LeavePreviewResponse preview = leaveService.preview(
                request(s, s.sickId(), "2026-06-01", "2026-07-15", false, false));
        var firstOfJuly = preview.days().stream()
                .filter(d -> d.date().equals(LocalDate.parse("2026-07-01"))).findFirst().orElseThrow();
        assertThat(firstOfJuly.extended()).isTrue();
        assertThat(firstOfJuly.streakDay()).isEqualTo(31);

        leaveService.apply(request(s, s.sickId(), "2026-06-01", "2026-07-15", false, false));
        entityManager.flush();

        assertThat(categoryOn(s, "2026-06-30")).isEqualTo("B");
        assertThat(categoryOn(s, "2026-07-01")).isEqualTo("B30");
        assertThat(categoryOn(s, "2026-07-15")).isEqualTo("B30");
    }

    @Test
    @DisplayName("weekends do not break the count — the days keep counting through them")
    void weekendsCountAsCalendarDays() {
        Setup s = setUp();
        leaveService.apply(request(s, s.sickId(), "2026-06-01", "2026-06-26", false, false));
        entityManager.flush();

        // Continue the following Monday: the weekend did not break the run, so
        // 2026-06-29 is day 29 (calendar), and day 31 falls on 2026-07-01.
        LeavePreviewResponse preview = leaveService.preview(
                request(s, s.sickId(), "2026-06-29", "2026-07-03", false, false));
        var monday = preview.days().getFirst();
        assertThat(monday.streakDay()).isEqualTo(29);
        assertThat(monday.extended()).isFalse();
        var wednesday = preview.days().stream()
                .filter(d -> d.date().equals(LocalDate.parse("2026-07-01"))).findFirst().orElseThrow();
        assertThat(wednesday.extended()).isTrue();
    }

    @Test
    @DisplayName("a vacation in between restarts the count")
    void aBreakRestartsTheCount() {
        Setup s = setUp();
        leaveService.apply(request(s, s.sickId(), "2026-06-01", "2026-06-25", false, false));
        leaveService.apply(request(s, s.vacationId(), "2026-06-26", "2026-06-26", false, false));
        entityManager.flush();

        // Sick again from Monday 06-29: the GO on Friday broke the run, so this
        // is day 1 — and thirty days later, 07-29 is the first EXTENDED day.
        LeavePreviewResponse preview = leaveService.preview(
                request(s, s.sickId(), "2026-06-29", "2026-08-05", false, false));
        assertThat(preview.days().getFirst().streakDay()).isEqualTo(1);
        var lastStandard = preview.days().stream()
                .filter(d -> d.date().equals(LocalDate.parse("2026-07-28"))).findFirst().orElseThrow();
        assertThat(lastStandard.extended()).isFalse();
        var firstExtended = preview.days().stream()
                .filter(d -> d.date().equals(LocalDate.parse("2026-07-29"))).findFirst().orElseThrow();
        assertThat(firstExtended.extended()).isTrue();
    }

    @Test
    @DisplayName("extended sick leave without thirty days of history needs the user's consent")
    void extendedWithoutHistoryNeedsConsent() {
        Setup s = setUp();

        LeavePreviewResponse preview = leaveService.preview(
                request(s, s.extendedId(), "2026-06-01", "2026-06-05", false, false));
        assertThat(preview.extendedWithoutHistory()).isTrue();

        assertThatThrownBy(() -> leaveService.apply(
                request(s, s.extendedId(), "2026-06-01", "2026-06-05", false, false)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("30");

        LeaveApplyResponse allowed = leaveService.apply(
                request(s, s.extendedId(), "2026-06-01", "2026-06-05", false, true));
        assertThat(allowed.createdShifts()).isEqualTo(5);
    }

    // ── Conflicts and closed months ──────────────────────────────────────────

    @Test
    @DisplayName("a shift of another type is archived only with consent, then the day is the leave")
    void conflictsNeedConsent() {
        Setup s = setUp();
        WorkShiftCreateRequest work = new WorkShiftCreateRequest();
        work.setEmployeeId(s.employeeId());
        work.setWorkDate("2026-06-03");
        work.setShiftType(shiftTemplateId());
        work.setWorkCategoryCodeId(s.workCategoryId());
        work.setSupervisorId(s.supervisorId());
        Long workShiftId = workShiftService.createShift(work).id();
        entityManager.flush();

        assertThatThrownBy(() -> leaveService.apply(
                request(s, s.sickId(), "2026-06-01", "2026-06-05", false, false)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("drugog tipa");

        LeaveApplyResponse response = leaveService.apply(
                request(s, s.sickId(), "2026-06-01", "2026-06-05", true, false));
        entityManager.flush();
        entityManager.clear();

        assertThat(response.archivedShifts()).isEqualTo(1);
        assertThat(response.createdShifts()).isEqualTo(5);
        assertThat(categoryOn(s, "2026-06-03")).isEqualTo("B");
        Object archivedAt = entityManager.createNativeQuery(
                "SELECT archived_at FROM work_shifts WHERE id = :id")
                .setParameter("id", workShiftId).getSingleResult();
        assertThat(archivedAt).isNotNull();
    }

    @Test
    @DisplayName("a month already handed to payroll refuses the whole entry")
    void closedMonthIsRefused() {
        Setup s = setUp();
        // The fixture's scenario builds September's payroll item; hand it over.
        entityManager.createNativeQuery("""
                UPDATE payroll_run_items SET status = 'APPROVED'
                WHERE employee_id = :employeeId
                  AND EXTRACT(YEAR FROM period) = 2026 AND EXTRACT(MONTH FROM period) = 9""")
                .setParameter("employeeId", s.employeeId())
                .executeUpdate();
        entityManager.flush();

        assertThatThrownBy(() -> leaveService.apply(
                request(s, s.sickId(), "2026-09-01", "2026-09-10", false, false)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("predati ili zaključani");
    }

    // ── The karton-creation sweep ────────────────────────────────────────────

    @Test
    @DisplayName("creating the next month's kartoni materialises the leave days it is owed")
    void materializationFillsTheNewMonth() {
        Setup s = setUp();
        // The period exists as intent only — as if July's kartoni did not exist
        // when it was entered.
        leaveRepository.save(EmployeeLeavePeriod.builder()
                .employee(entityManager.getReference(
                        com.aleksandarparipovic.marel_app.employee.Employee.class, s.employeeId()))
                .workCodeCategory(entityManager.getReference(
                        com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory.class, s.sickId()))
                .dateFrom(LocalDate.parse("2026-06-22"))
                .dateTo(LocalDate.parse("2026-07-10"))
                .build());
        entityManager.flush();

        var result = leaveService.materializeForMonthAll(2026, 7);
        entityManager.flush();

        // July 1–10 holds eight weekdays; June was not asked for and stays empty.
        assertThat(result.createdShifts()).isEqualTo(8);
        assertThat(result.conflicts()).isEmpty();
        assertThat(categoryOn(s, "2026-07-01")).isEqualTo("B");
        assertThat(categoryOn(s, "2026-06-22")).isNull();
    }

    // ── Range archiving ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a range archive withdraws every live shift in it")
    void archiveRangeWithdrawsTheRange() {
        Setup s = setUp();
        leaveService.apply(request(s, s.sickId(), "2026-06-01", "2026-06-05", false, false));
        entityManager.flush();

        var summary = workShiftService.archiveRange(
                s.employeeId(), LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-05"),
                "pogrešan unos");
        entityManager.flush();

        assertThat(summary.shifts()).isEqualTo(5);
        assertThat(liveShiftCount(s)).isZero();
    }

    @Test
    @DisplayName("a range archive refuses a handed-over month whole")
    void archiveRangeRefusesClosedMonth() {
        Setup s = setUp();
        WorkShiftCreateRequest work = new WorkShiftCreateRequest();
        work.setEmployeeId(s.employeeId());
        work.setWorkDate("2026-09-02");
        work.setShiftType(shiftTemplateId());
        work.setWorkCategoryCodeId(s.workCategoryId());
        work.setSupervisorId(s.supervisorId());
        workShiftService.createShift(work);
        entityManager.createNativeQuery("""
                UPDATE payroll_run_items SET status = 'LOCKED'
                WHERE employee_id = :employeeId
                  AND EXTRACT(YEAR FROM period) = 2026 AND EXTRACT(MONTH FROM period) = 9""")
                .setParameter("employeeId", s.employeeId())
                .executeUpdate();
        entityManager.flush();

        assertThatThrownBy(() -> workShiftService.archiveRange(
                s.employeeId(), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("zaključan");
    }

    private Long shiftTemplateId() {
        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM shifts ORDER BY start_time LIMIT 1")
                .getSingleResult()).longValue();
    }
}
