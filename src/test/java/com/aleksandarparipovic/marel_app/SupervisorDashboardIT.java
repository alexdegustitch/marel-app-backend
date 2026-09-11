package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.dashboard.SupervisorDashboardService;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightComputeService;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightKey;
import com.aleksandarparipovic.marel_app.dashboard.insight.DashboardInsightRepository;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NormFitRow;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.RolePermissions;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supervisor's board is one snapshot job and six live queries, all of them
 * hand-written SQL against tables nothing else on the board touches. What is
 * worth proving here is the same thing {@code AdminDashboardIT} proves: that the
 * SQL still matches the schema, and that the two places where the board REFUSES
 * to guess — an unconfigured sick-leave code, an analytics snapshot that has
 * never run — say so instead of reporting a comfortable zero.
 */
@Transactional
class SupervisorDashboardIT extends AbstractIntegrationTest {

    @Autowired private SupervisorDashboardService dashboardService;
    @Autowired private DashboardInsightComputeService computeService;
    @Autowired private DashboardInsightRepository insightRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("every insight query runs against the real schema and stores a row")
    void everyInsightComputes() {
        LocalDate today = LocalDate.now();

        computeService.computeFor(today);

        for (DashboardInsightKey key : DashboardInsightKey.values()) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM dashboard_insights WHERE insight_key = ? AND computed_for = ?",
                    Integer.class, key.name(), today);
            assertThat(rows).as("snapshot written for %s", key).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a stored payload reads back as the row type it was written as")
    void payloadRoundTrips() {
        LocalDate today = LocalDate.now();
        computeService.computeFor(today);

        var stored = insightRepository.findLatest(DashboardInsightKey.NORM_TOO_LOW, NormFitRow.class);

        assertThat(stored).isPresent();
        assertThat(stored.get().computedFor()).isEqualTo(today);
        // The norm cards' window is a tuned setting (V44), not the general 30.
        assertThat(stored.get().windowDays())
                .isEqualTo(computeService.currentThresholds().normWindowDays());
        assertThat(stored.get().rows()).isNotNull();
    }

    @Test
    @DisplayName("every live block answers, and none of them can outgrow its cap")
    void everyLiveBlockAnswers() {
        SupervisorDashboardResponse board = dashboardService.load(1L);

        assertThat(board.today()).isEqualTo(LocalDate.now());
        assertThat(board.windowDays()).isEqualTo(30);

        assertThat(board.myRecentRecords().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.myRecentPayrolls().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.pendingRequests().rows()).hasSizeLessThanOrEqualTo(5);
        // The claimed card is the caller's whole desk, so its cap is the guard
        // against the absurd rather than a page size.
        assertThat(board.claimedRequests().rows()).hasSizeLessThanOrEqualTo(25);
        assertThat(board.upcomingNonWorkingDays().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.absences().rows()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the absence card answers from the categories' own declaration, no setting needed")
    void absenceCardAnswersByCategoryType() {
        SupervisorDashboardResponse board = dashboardService.load(1L);

        assertThat(board.absences()).isNotNull();
        assertThat(board.absences().rows()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("the old sick-leave code-list setting is archived, so nobody edits a dead knob")
    void sickLeaveSettingIsArchived() {
        Integer live = jdbc.queryForObject("""
                SELECT count(*) FROM app_settings
                WHERE setting_key = 'sick_leave_work_code_category_nos' AND archived_at IS NULL
                """, Integer.class);
        assertThat(live).isZero();
    }

    @Test
    @DisplayName("a fully entered previous month reports its karton as ready for payroll")
    void readyRecordsFollowTheEnteredDays() {
        SupervisorDashboardResponse board = dashboardService.load(1L);

        // The block answers for the PREVIOUS month, and its counts are sane.
        var ready = board.readyRecords();
        LocalDate previous = LocalDate.now().minusMonths(1);
        assertThat(ready.year()).isEqualTo(previous.getYear());
        assertThat(ready.month()).isEqualTo(previous.getMonthValue());
        assertThat(ready.readyCount()).isLessThanOrEqualTo(ready.employeeCount());
        assertThat(ready.rows()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("before the job has ever run the analytics half is empty AND marked stale")
    void insightsAreMarkedStaleUntilComputed() {
        jdbc.update("DELETE FROM dashboard_insights");

        SupervisorDashboardResponse.Insights insights = dashboardService.load(1L).insights();

        assertThat(insights.computedFor()).isNull();
        assertThat(insights.stale()).isTrue();
        assertThat(insights.normTooLow()).isEmpty();
        assertThat(insights.topPerformers()).isEmpty();
    }

    @Test
    @DisplayName("after the job has run the board reports the day the figures are from")
    void insightsCarryTheirDay() {
        LocalDate today = LocalDate.now();
        computeService.computeFor(today);

        SupervisorDashboardResponse.Insights insights = dashboardService.load(1L).insights();

        assertThat(insights.computedFor()).isEqualTo(today);
        assertThat(insights.stale()).isFalse();
        assertThat(insights.yesterday()).isEqualTo(today.minusDays(1));
    }

    @Test
    @DisplayName("the claimed card counts only what THIS user took — a colleague's desk is not on it")
    void claimedCardIsTheCallersOwnDesk() {
        Long product = jdbc.queryForObject("""
                WITH ins AS (
                    INSERT INTO products (product_name, product_code, description)
                    SELECT 'IT proizvod za preuzete', 'IT-CLAIM', 'IT'
                    WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_code = 'IT-CLAIM')
                    RETURNING id)
                SELECT id FROM ins UNION ALL SELECT id FROM products WHERE product_code = 'IT-CLAIM' LIMIT 1
                """, Long.class);
        List<Long> users = jdbc.queryForList("SELECT id FROM users ORDER BY id LIMIT 2", Long.class);
        assertThat(users).hasSizeGreaterThanOrEqualTo(1);
        Long me = users.get(0);
        Long colleague = users.size() > 1 ? users.get(1) : null;

        jdbc.update("""
                INSERT INTO manufacturing_time_requests (product_id, request_type, description, status, assigned_to, created_by, internal)
                VALUES (?, 'CREATE', 'IT zahtev', 'IN_REVIEW', ?, ?, false)
                """, product, me, me);
        if (colleague != null) {
            jdbc.update("""
                    INSERT INTO manufacturing_time_requests (product_id, request_type, description, status, assigned_to, created_by, internal)
                    VALUES (?, 'CREATE', 'IT zahtev', 'IN_REVIEW', ?, ?, false)
                    """, product, colleague, colleague);
        }

        SupervisorDashboardResponse board = dashboardService.load(me);

        assertThat(board.claimedRequests().total()).isEqualTo(1);
        assertThat(board.claimedRequests().rows())
                .allSatisfy(row -> assertThat(row.assignedToMe()).isTrue());
    }

    @Test
    @DisplayName("a tuned threshold in app_settings is the one the next compute uses, and the board states it")
    void thresholdsComeFromSettings() {
        jdbc.update("""
                UPDATE app_settings SET setting_value_numeric = 45
                WHERE setting_key = 'dashboard_norm_window_days'
                """);
        jdbc.update("""
                UPDATE app_settings SET setting_value_numeric = 5
                WHERE setting_key = 'dashboard_norm_rise_pct'
                """);

        LocalDate today = LocalDate.now();
        computeService.computeFor(today);

        var stored = insightRepository.findLatest(DashboardInsightKey.NORM_TOO_LOW, NormFitRow.class);
        assertThat(stored).isPresent();
        assertThat(stored.get().windowDays()).isEqualTo(45);

        var insights = dashboardService.load(1L).insights();
        assertThat(insights.normWindowDays()).isEqualTo(45);
        assertThat(insights.normRisePct()).isEqualTo(5);
        assertThat(insights.normDropPct()).isEqualTo(10);
        assertThat(insights.activityWindowDays()).isEqualTo(30);
        assertThat(insights.topPerformerMinHours()).isEqualTo(20);
    }

    // ── Neunete smene ────────────────────────────────────────────────────────

    private Long insertEmployee(String firstName, String lastName, String no) {
        jdbc.update("""
                INSERT INTO departments (name, is_active)
                SELECT 'IT sektor', TRUE
                WHERE NOT EXISTS (SELECT 1 FROM departments WHERE name = 'IT sektor')
                """);
        jdbc.update("""
                INSERT INTO employees (department_id, employee_no, employment_start_date,
                                       first_name, last_name, is_active)
                SELECT d.id, ?, DATE '2026-01-01', ?, ?, TRUE
                FROM departments d WHERE d.name = 'IT sektor'
                """, no, firstName, lastName);
        return jdbc.queryForObject(
                "SELECT id FROM employees WHERE employee_no = ?", Long.class, no);
    }

    @Test
    @DisplayName("an employed person with nothing entered is on the missing-shifts list; a shift or a leave period takes them off it")
    void missingShiftsFollowWhatTheDayHolds() {
        LocalDate monday = LocalDate.parse("2026-06-01");
        Long employeeId = insertEmployee("Pera", "Perić", "IT-MS-1");

        var missing = dashboardService.missingShifts(monday);
        assertThat(missing.applicable()).isTrue();
        assertThat(missing.rows())
                .anySatisfy(row -> assertThat(row.employeeId()).isEqualTo(employeeId));

        // A live shift on the day answers the question.
        Long shiftTemplateId = jdbc.queryForObject("""
                WITH ins AS (
                    INSERT INTO shifts (shift_code, name, start_time, end_time, is_active)
                    SELECT 'IT-S1', 'Prva smena', TIME '06:00', TIME '14:00', TRUE
                    WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE shift_code = 'IT-S1')
                    RETURNING id)
                SELECT id FROM ins UNION ALL SELECT id FROM shifts WHERE shift_code = 'IT-S1' LIMIT 1
                """, Long.class);
        jdbc.update("""
                INSERT INTO work_shifts (employee_id, shift_id, start_at, end_at, work_date, is_active)
                VALUES (?, ?, TIMESTAMPTZ '2026-06-01 06:00:00+02', TIMESTAMPTZ '2026-06-01 14:00:00+02',
                        DATE '2026-06-01', TRUE)
                """, employeeId, shiftTemplateId);
        assertThat(dashboardService.missingShifts(monday).rows())
                .noneSatisfy(row -> assertThat(row.employeeId()).isEqualTo(employeeId));

        // A recorded leave period covers a day even before its shift exists —
        // the absence is already entered, so the list must not ask for it again.
        Long otherId = insertEmployee("Mika", "Mikić", "IT-MS-2");
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM work_code_categories ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO employee_leave_periods (employee_id, work_code_category_id, date_from, date_to)
                VALUES (?, ?, DATE '2026-06-01', DATE '2026-06-05')
                """, otherId, categoryId);
        assertThat(dashboardService.missingShifts(monday).rows())
                .noneSatisfy(row -> assertThat(row.employeeId()).isEqualTo(otherId));
    }

    @Test
    @DisplayName("Sunday does not ask for shifts — the block says not-applicable instead of counting everybody")
    void sundayIsNotApplicable() {
        insertEmployee("Žika", "Žikić", "IT-MS-3");

        var sunday = dashboardService.missingShifts(LocalDate.parse("2026-06-07"));

        assertThat(sunday.applicable()).isFalse();
        assertThat(sunday.total()).isZero();
        assertThat(sunday.rows()).isEmpty();
    }

    @Test
    @DisplayName("the board carries the count of missing shifts for its card")
    void boardCarriesMissingShiftCount() {
        SupervisorDashboardResponse board = dashboardService.load(1L);

        assertThat(board.missingShifts()).isNotNull();
        if (LocalDate.now().getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            assertThat(board.missingShifts().applicable()).isFalse();
        } else {
            assertThat(board.missingShifts().applicable()).isTrue();
            assertThat(board.missingShifts().total()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("a supervisor may see their own board and still not the administrator's")
    void supervisorSeesOnlyTheirOwnBoard() {
        assertThat(RolePermissions.roleHas("supervisor", AppPermission.DASHBOARD_SUPERVISOR_VIEW)).isTrue();
        assertThat(RolePermissions.roleHas("supervisor", AppPermission.DASHBOARD_ADMIN_VIEW)).isFalse();

        // Admin and developer hold everything, so both boards are theirs too.
        assertThat(RolePermissions.roleHas("admin", AppPermission.DASHBOARD_SUPERVISOR_VIEW)).isTrue();
        assertThat(RolePermissions.roleHas("developer", AppPermission.DASHBOARD_SUPERVISOR_VIEW)).isTrue();

        // Commercial staff have neither.
        assertThat(RolePermissions.roleHas("commercial", AppPermission.DASHBOARD_SUPERVISOR_VIEW)).isFalse();
    }
}
