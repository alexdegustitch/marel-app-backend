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
 * The control board is ten queries against eight tables, so the thing worth
 * proving is that each one still matches the schema and answers about the right
 * rows — a renamed column or a wrong join would otherwise only be found by an
 * administrator opening the page.
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
        assertThat(board.windowDays()).isEqualTo(30);

        // Each block is present and never over its cap, whatever the data holds.
        assertThat(board.readyPayrolls().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.newUsers().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.newProducts().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.newOperations().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.newProductionOrders().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.newSampleOrders().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.nearestDeadlines().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.upcomingNonWorkingDays().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.registrationRequests().rows()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.norms().best()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.norms().worst()).hasSizeLessThanOrEqualTo(5);
        assertThat(board.norms().from()).isEqualTo(LocalDate.now().minusDays(30));
    }

    @Test
    @DisplayName("a fresh registration shows up as a new user AND as a pending request")
    void newRegistrationAppearsInBothBlocks() {
        long usersBefore = dashboardService.load().newUsers().total();

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

        AdminDashboardResponse board = dashboardService.load();

        assertThat(board.newUsers().total()).isEqualTo(usersBefore + 1);
        assertThat(board.newUsers().rows())
                .anyMatch(row -> "Kontrolna Tabla".equals(row.fullName()));
        assertThat(board.registrationRequests().rows())
                .anyMatch(row -> "Kontrolna Tabla".equals(row.fullName()));
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
