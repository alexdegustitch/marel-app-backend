package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.dashboard.SupervisorDashboardService;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two lookups that let one screen send somebody straight into another.
 *
 * <p>A karton is addressed by its own id and a payroll month by its monthly
 * report, and neither is something the calling screen holds — the calendar has
 * an employee and a month, the control board has a payroll item. Both of these
 * are therefore reads that exist ONLY so a link can be built, which is exactly
 * the kind that breaks unnoticed: nothing else calls them, and a wrong answer
 * shows up as a page that opens empty rather than as an error.
 */
@Transactional
class KartonDeepLinkIT extends AbstractIntegrationTest {

    @Autowired private EmployeeRecordService employeeRecordService;
    @Autowired private SupervisorDashboardService dashboardService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private JdbcTemplate jdbc;

    private static final YearMonth PERIOD = YearMonth.of(2026, 4);

    @Test
    @DisplayName("a month with a karton answers with its id")
    void findsTheKartonForAMonth() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(PERIOD).build();

        var found = employeeRecordService.findRecordIdForEmployeeAndMonth(
                scenario.employee().getId(), PERIOD.getYear(), PERIOD.getMonthValue());

        assertThat(found).contains(scenario.employeeRecord().getId());
    }

    /**
     * The answer a calendar needs for a month nobody has opened yet. Empty, not
     * an exception: "there is no karton" is an ordinary state of a month, and the
     * calendar shows the days without a way in rather than failing to draw.
     */
    @Test
    @DisplayName("a month without a karton answers with nothing, and does not throw")
    void findsNothingForAMonthWithoutAKarton() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(PERIOD).build();

        var found = employeeRecordService.findRecordIdForEmployeeAndMonth(
                scenario.employee().getId(), PERIOD.getYear(), PERIOD.getMonthValue() + 1);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("an employee who does not exist answers with nothing rather than someone else's karton")
    void findsNothingForAnUnknownEmployee() {
        var found = employeeRecordService.findRecordIdForEmployeeAndMonth(
                -1L, PERIOD.getYear(), PERIOD.getMonthValue());

        assertThat(found).isEmpty();
    }

    /**
     * The control board's payroll card carries the monthly report id, because
     * that — and not the payroll item's own id — is what the payroll screen is
     * addressed by. Without it the card could only link to the list.
     */
    @Test
    @DisplayName("the board's payroll row carries what the payroll screen is addressed by")
    void payrollRowCarriesItsMonthlyReport() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(PERIOD).build();
        Long userId = jdbc.queryForObject("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);
        assertThat(userId).as("the bootstrap accounts exist").isNotNull();

        jdbc.update("""
                INSERT INTO employee_payroll_run_item_updates (payroll_run_item_id, user_id, last_activity_at)
                VALUES (?, ?, now())
                """, scenario.item().getId(), userId);

        SupervisorDashboardResponse board = dashboardService.load(userId);

        assertThat(board.myRecentPayrolls().rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.payrollRunItemId()).isEqualTo(scenario.item().getId());
                    assertThat(row.monthlyReportId()).isEqualTo(scenario.monthlyReport().getId());
                    assertThat(row.employeeId()).isEqualTo(scenario.employee().getId());
                });
    }

    /**
     * And the karton card carries the record id, which is what the karton is
     * addressed by. The same shape of claim, for the other half of the pair.
     */
    @Test
    @DisplayName("the board's karton row carries what the karton is addressed by")
    void kartonRowCarriesItsRecord() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(PERIOD).build();
        Long userId = jdbc.queryForObject("SELECT id FROM users ORDER BY id LIMIT 1", Long.class);

        jdbc.update("""
                INSERT INTO employee_record_updates (employee_record_id, user_id, last_activity_at)
                VALUES (?, ?, now())
                """, scenario.employeeRecord().getId(), userId);

        SupervisorDashboardResponse board = dashboardService.load(userId);

        assertThat(board.myRecentRecords().rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.employeeRecordId()).isEqualTo(scenario.employeeRecord().getId());
                    assertThat(row.periodStart()).isEqualTo(PERIOD.atDay(1));
                });
    }
}
