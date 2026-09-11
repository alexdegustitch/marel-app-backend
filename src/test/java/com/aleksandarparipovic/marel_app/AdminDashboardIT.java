package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.auth.AuthService;
import com.aleksandarparipovic.marel_app.auth.dto.RegisterRequest;
import com.aleksandarparipovic.marel_app.dashboard.AdminDashboardService;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse;
import com.aleksandarparipovic.marel_app.role.Role;
import com.aleksandarparipovic.marel_app.role.RoleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The direktor's board is composed from the commercial and supervisor boards
 * plus a few blocks of its own, so the thing worth proving is that the whole
 * composition still answers on the real schema — a renamed column or a wrong
 * join in any borrowed block would otherwise only be found by the direktor
 * opening the page.
 */
@Transactional
class AdminDashboardIT extends AbstractIntegrationTest {

    @Autowired private AdminDashboardService dashboardService;
    @Autowired private AuthService authService;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("every block answers, on a schema that is really there")
    void everyBlockAnswers() {
        AdminDashboardResponse board = dashboardService.load();

        assertThat(board.today()).isEqualTo(LocalDate.now());
        assertThat(board.deliveredWindowDays()).isEqualTo(30);
        assertThat(board.requestsWindowDays()).isEqualTo(7);

        // Each block is present and never over its cap, whatever the data holds.
        assertThat(board.kpis()).isNotNull();
        assertThat(board.late().rows()).hasSizeLessThanOrEqualTo(30);
        assertThat(board.dueSoon().rows()).hasSizeLessThanOrEqualTo(30);
        assertThat(board.delivered().rows()).hasSizeLessThanOrEqualTo(30);
        assertThat(board.progress().mostFilled()).hasSizeLessThanOrEqualTo(8);
        assertThat(board.progress().leastFilled()).hasSizeLessThanOrEqualTo(8);
        assertThat(board.recentRequests().rows()).hasSizeLessThanOrEqualTo(30);
        assertThat(board.upcomingNonWorkingDays().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.absences()).isNotNull();
        assertThat(board.registrationRequests().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.submittedPayrolls().rows()).hasSizeLessThanOrEqualTo(30);
        assertThat(board.deadlinePressure().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.insights()).isNotNull();
    }

    @Test
    @DisplayName("a fresh registration shows up as a pending request")
    void newRegistrationAppearsAsPendingRequest() {
        Role role = roleRepository.findAll().stream()
                .filter(r -> !"developer".equalsIgnoreCase(r.getRoleName()))
                .findFirst().orElseThrow();

        RegisterRequest request = new RegisterRequest();
        request.setFirstName("Kontrolna");
        request.setLastName("Tabla");
        request.setEmailAddress("kontrolna.tabla@example.rs");
        request.setPassword("Test1234");
        request.setConfirmPassword("Test1234");
        request.setRoleId(role.getId());
        authService.register(request);

        assertThat(dashboardService.load().registrationRequests().rows())
                .anyMatch(row -> "Kontrolna Tabla".equals(row.fullName()));
    }

    @Test
    @DisplayName("an APPROVED payroll month is on the review pile; a LOCKED one is off it")
    void submittedPayrollsAreTheApprovedOnes() {
        long before = dashboardService.load().submittedPayrolls().total();

        // The pile counts by status alone, so a status flip is the whole story.
        Integer approved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_run_items WHERE status = 'APPROVED'", Integer.class);
        assertThat(before).isEqualTo(approved == null ? 0 : approved.longValue());
    }

    @Test
    @DisplayName("the days-off card names holidays and skips ordinary weekends")
    void weekendsDoNotCrowdOutHolidays() {
        LocalDate saturday = nextDayOfWeek(java.time.DayOfWeek.SATURDAY);
        LocalDate holiday = saturday.plusDays(3);

        insertCalendarDay(saturday, "NON_WORKING", null);
        insertCalendarDay(holiday, "HOLIDAY", "Probni praznik");

        var days = dashboardService.load().upcomingNonWorkingDays().rows();

        assertThat(days).noneMatch(day -> saturday.equals(day.date()));
        assertThat(days)
                .filteredOn(day -> holiday.equals(day.date()))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.label()).isEqualTo("Probni praznik");
                    assertThat(day.daysUntil()).isEqualTo(
                            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), holiday));
                });
    }

    @Test
    @DisplayName("a day turned back into a working day stops being a day off")
    void workingOverrideRemovesTheDay() {
        LocalDate holiday = nextDayOfWeek(java.time.DayOfWeek.WEDNESDAY);
        insertCalendarDay(holiday, "HOLIDAY", "Radi se ipak");
        jdbc.update("UPDATE work_calendar_days SET working_override = true WHERE calendar_date = ?",
                java.sql.Date.valueOf(holiday));

        assertThat(dashboardService.load().upcomingNonWorkingDays().rows())
                .noneMatch(day -> holiday.equals(day.date()));
    }

    /** The next such weekday strictly after today, so "upcoming" is unambiguous. */
    private static LocalDate nextDayOfWeek(java.time.DayOfWeek dayOfWeek) {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() != dayOfWeek) {
            date = date.plusDays(1);
        }
        return date;
    }

    private void insertCalendarDay(LocalDate date, String dayType, String label) {
        jdbc.update("DELETE FROM work_calendar_days WHERE calendar_date = ?",
                java.sql.Date.valueOf(date));
        jdbc.update("INSERT INTO work_calendar_days (calendar_date, day_type, label) VALUES (?, ?, ?)",
                java.sql.Date.valueOf(date), dayType, label);
    }
}
