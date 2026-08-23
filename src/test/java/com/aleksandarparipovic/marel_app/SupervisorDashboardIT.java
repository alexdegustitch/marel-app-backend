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
        assertThat(stored.get().windowDays()).isEqualTo(DashboardInsightComputeService.WINDOW_DAYS);
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
        assertThat(board.claimedRequests().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.upcomingNonWorkingDays().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.absences().rows()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("with no sick-leave code configured the card says so, it does not say nobody is out")
    void unconfiguredAbsenceIsNotAnEmptyAnswer() {
        SupervisorDashboardResponse board = dashboardService.load(1L);

        assertThat(board.absences().configured()).isFalse();
        assertThat(board.absences().total()).isZero();
        assertThat(board.absences().rows()).isEmpty();
    }

    @Test
    @DisplayName("once a code is named the absence card starts answering from it")
    void configuredAbsenceQueryRuns() {
        String categoryNo = jdbc.queryForObject(
                "SELECT category_no FROM work_code_categories ORDER BY id LIMIT 1", String.class);
        assertThat(categoryNo).as("the seed data has at least one work code").isNotBlank();

        jdbc.update("""
                UPDATE app_settings SET setting_value_text = ?
                WHERE setting_key = 'sick_leave_work_code_category_nos'
                """, categoryNo);

        SupervisorDashboardResponse board = dashboardService.load(1L);

        assertThat(board.absences().configured()).isTrue();
        assertThat(board.absences().rows()).hasSizeLessThanOrEqualTo(5);
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
