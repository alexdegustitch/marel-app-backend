package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employment_period.EmployeeEmploymentPeriod;
import com.aleksandarparipovic.marel_app.employment_period.EmployeeEmploymentPeriodRepository;
import com.aleksandarparipovic.marel_app.employment_period.EmploymentPeriodService;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the employment start date and the probation length may still be changed.
 *
 * <p>Both are ordinary-looking fields that anchor calculation behind them, so
 * each has a rule about when it is still free:
 *
 * <ul>
 *   <li>the START DATE, until any WORK log exists — before the employee has
 *       started, and after, for as long as nothing has been worked;</li>
 *   <li>the PROBATION LENGTH, only while probation is still running, and never
 *       shortened into the past — days already served on probation stay
 *       probation.</li>
 * </ul>
 *
 * <p>A unit test: both rules are decisions about dates and one existence check,
 * so no database is involved.
 */
class EmploymentEditRulesTest {

    private static final Long EMPLOYEE_ID = 7L;

    private EmployeeEmploymentPeriodRepository periods;
    private WorkLogRepository workLogs;
    private EmploymentPeriodService service;

    @BeforeEach
    void setUp() {
        periods = mock(EmployeeEmploymentPeriodRepository.class);
        workLogs = mock(WorkLogRepository.class);
        service = new EmploymentPeriodService(periods, workLogs);
        when(periods.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** A spell that started `startedDaysAgo` ago with `graceDays` of probation. */
    private EmployeeEmploymentPeriod spell(long startedDaysAgo, int graceDays) {
        LocalDate started = LocalDate.now().minusDays(startedDaysAgo);
        EmployeeEmploymentPeriod period = EmployeeEmploymentPeriod.builder()
                .id(1L)
                .startedOn(started)
                .normGraceDays(graceDays)
                .build();
        // Mirrors the generated column: probation ends `graceDays` after the start.
        period.setProbationEndDate(started.plusDays(graceDays));
        when(periods.findLatestOne(EMPLOYEE_ID)).thenReturn(Optional.of(period));
        return period;
    }

    /* ── Start date ─────────────────────────────────────────────────────── */

    @Test
    @DisplayName("start date moves freely while no WORK log exists")
    void startDateMovesWithoutWork() {
        EmployeeEmploymentPeriod period = spell(10, 30);
        when(workLogs.existsWorkTypeLogForEmployee(EMPLOYEE_ID)).thenReturn(false);

        LocalDate moved = LocalDate.now().minusDays(3);
        service.applyEditedDates(EMPLOYEE_ID, moved, null);

        assertThat(period.getStartedOn()).isEqualTo(moved);
    }

    @Test
    @DisplayName("start date is refused once the employee has worked")
    void startDateRefusedAfterWork() {
        EmployeeEmploymentPeriod period = spell(10, 30);
        LocalDate original = period.getStartedOn();
        when(workLogs.existsWorkTypeLogForEmployee(EMPLOYEE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.applyEditedDates(EMPLOYEE_ID, LocalDate.now().minusDays(3), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidentiran rad");

        assertThat(period.getStartedOn()).isEqualTo(original);
    }

    @Test
    @DisplayName("resending the SAME start date is not a change, so it is not refused")
    void unchangedStartDatePasses() {
        EmployeeEmploymentPeriod period = spell(10, 30);
        when(workLogs.existsWorkTypeLogForEmployee(EMPLOYEE_ID)).thenReturn(true);

        // The screen sends the whole form on every save; an employee who has
        // worked must still be able to have their phone number corrected.
        service.applyEditedDates(EMPLOYEE_ID, period.getStartedOn(), null);

        assertThat(period.getStartedOn()).isEqualTo(LocalDate.now().minusDays(10));
    }

    /* ── Probation length ───────────────────────────────────────────────── */

    @Test
    @DisplayName("probation can be extended while it is still running")
    void probationExtends() {
        EmployeeEmploymentPeriod period = spell(10, 30);

        Optional<LocalDate[]> affected = service.changeProbationDays(EMPLOYEE_ID, 45);

        assertThat(period.getNormGraceDays()).isEqualTo(45);
        assertThat(affected).isPresent();
    }

    @Test
    @DisplayName("probation can be shortened, but only down to the days already served")
    void probationShortensToToday() {
        EmployeeEmploymentPeriod period = spell(10, 30);

        // Exactly today: the last allowed value.
        service.changeProbationDays(EMPLOYEE_ID, 10);

        assertThat(period.getNormGraceDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("probation cannot be shortened into the past")
    void probationCannotShortenIntoThePast() {
        EmployeeEmploymentPeriod period = spell(10, 30);

        assertThatThrownBy(() -> service.changeProbationDays(EMPLOYEE_ID, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 dana");

        assertThat(period.getNormGraceDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("probation cannot be changed once it is over")
    void probationLockedAfterItEnds() {
        EmployeeEmploymentPeriod period = spell(60, 30);

        assertThatThrownBy(() -> service.changeProbationDays(EMPLOYEE_ID, 90))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dok probni period traje");

        assertThat(period.getNormGraceDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("resending the SAME probation length is a no-op, even after it ended")
    void unchangedProbationPasses() {
        EmployeeEmploymentPeriod period = spell(60, 30);

        Optional<LocalDate[]> affected = service.changeProbationDays(EMPLOYEE_ID, 30);

        assertThat(affected).isEmpty();
        assertThat(period.getNormGraceDays()).isEqualTo(30);
        verify(periods, never()).saveAndFlush(any());
    }
}
