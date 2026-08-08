package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.payroll_calculation.calculators.TransportAllowanceCalculator;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transport is paid per ARRIVAL — per journey to work.
 *
 * <p>Three different numbers can be produced from the same month and only one of
 * them is the fare that was actually paid:
 *
 * <pre>
 *   first shift, straight into the second   1 day   2 shifts   1 ARRIVAL
 *   first shift, home, then the third       1 day   2 shifts   2 ARRIVALS
 * </pre>
 *
 * <p>Per day underpays the second case; per shift — which this counted until
 * 2026-09-13 — overpays the first. The employee did not go anywhere at a shift
 * changeover, and did go home in between when there is a gap.
 *
 * <p>The threshold is {@link TransportAllowanceCalculator#ARRIVAL_GAP_MINUTES}.
 */
@Transactional
class TransportPerArrivalIT extends AbstractIntegrationTest {

    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private PayrollScenarioFixture fixture;

    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
    private static final LocalDate TO = LocalDate.of(2026, 7, 31);
    private static final int GAP = TransportAllowanceCalculator.ARRIVAL_GAP_MINUTES;

    private Employee anEmployee() {
        return fixture.scenario().build().employee();
    }

    private long arrivals(Employee employee) {
        return dailyReportRepository.countQualifyingArrivals(employee.getId(), FROM, TO, GAP);
    }

    // ─── the two cases the rule exists for ──────────────────────────────────

    @Test
    @DisplayName("two shifts back to back on one day are ONE arrival")
    void consecutiveShiftsAreOneArrival() {
        Employee employee = anEmployee();
        LocalDate day = LocalDate.of(2026, 7, 6);

        // 06:00-14:00 then 14:00-22:00 — the changeover is not a journey home.
        fixture.dailyReport(employee, day, 6, 480, 480);
        fixture.dailyReport(employee, day, 14, 480, 480);

        assertThat(arrivals(employee)).isEqualTo(1);
    }

    @Test
    @DisplayName("first shift and then the third, with the afternoon off, are TWO arrivals")
    void shiftsWithAGapAreTwoArrivals() {
        Employee employee = anEmployee();
        LocalDate day = LocalDate.of(2026, 7, 7);

        // 06:00-14:00, then nothing until 22:00. Eight hours is going home.
        // This is the shape of the case that was reported: employee 3, 2026-07-01.
        fixture.dailyReport(employee, day, 6, 480, 240);
        fixture.dailyReport(employee, day, 22, 480, 300);

        assertThat(arrivals(employee)).isEqualTo(2);
    }

    // ─── the threshold itself ───────────────────────────────────────────────

    @Test
    @DisplayName("a break of exactly the threshold still chains; longer starts a new arrival")
    void thresholdBoundary() {
        Employee onTheLine = anEmployee();
        LocalDate day = LocalDate.of(2026, 7, 8);
        // 06:00-13:00, then 14:00 — exactly 60 minutes. The rule is "more than",
        // so this is still one journey.
        fixture.dailyReport(onTheLine, day, 6, 420, 420);
        fixture.dailyReport(onTheLine, day, 14, 480, 480);
        assertThat(arrivals(onTheLine)).isEqualTo(1);

        Employee justOver = anEmployee();
        // 06:00-12:59, then 14:00 — 61 minutes.
        fixture.dailyReport(justOver, day, 6, 419, 419);
        fixture.dailyReport(justOver, day, 14, 480, 480);
        assertThat(arrivals(justOver)).isEqualTo(2);
    }

    // ─── what must NOT change ───────────────────────────────────────────────

    @Test
    @DisplayName("one shift a day is one arrival a day, exactly as before")
    void ordinaryMonthIsUnchanged() {
        Employee employee = anEmployee();
        for (int day = 1; day <= 5; day++) {
            fixture.dailyReport(employee, LocalDate.of(2026, 7, day), 6, 480, 480);
        }
        assertThat(arrivals(employee)).isEqualTo(5);
    }

    @Test
    @DisplayName("a shift that crosses midnight is one arrival, not two")
    void overnightShiftIsOneArrival() {
        Employee employee = anEmployee();
        // 22:00 on the 9th to 06:00 on the 10th. One journey, and work_date is the
        // day it started on.
        fixture.dailyReport(employee, LocalDate.of(2026, 7, 9), 22, 480, 480);
        assertThat(arrivals(employee)).isEqualTo(1);
    }

    @Test
    @DisplayName("a night shift and the morning shift that follows it are ONE arrival across midnight")
    void chainAcrossMidnight() {
        Employee employee = anEmployee();
        // 22:00-06:00, then 06:00-14:00 the NEXT calendar day. Two work_dates, one
        // journey. Counting per day, or ordering within a day, would pay twice.
        fixture.dailyReport(employee, LocalDate.of(2026, 7, 13), 22, 480, 480);
        fixture.dailyReport(employee, LocalDate.of(2026, 7, 14), 6, 480, 480);

        assertThat(arrivals(employee)).isEqualTo(1);
    }

    @Test
    @DisplayName("a shift with no work minutes earns nothing and does not link the shifts around it")
    void absentShiftIsNotAnArrival() {
        Employee employee = anEmployee();
        LocalDate day = LocalDate.of(2026, 7, 15);

        // Present 06:00-14:00, absent for 14:00-22:00. The second is not a journey
        // and must not extend the first into one long chain either.
        fixture.dailyReport(employee, day, 6, 480, 480);
        fixture.dailyReport(employee, day, 14, 480, 0);

        assertThat(arrivals(employee)).isEqualTo(1);
    }

    @Test
    @DisplayName("no work in the period is no arrivals")
    void emptyPeriod() {
        assertThat(arrivals(anEmployee())).isZero();
    }

    @Test
    @DisplayName("work outside the period is not counted")
    void periodIsRespected() {
        Employee employee = anEmployee();
        fixture.dailyReport(employee, LocalDate.of(2026, 6, 30), 6, 480, 480);
        fixture.dailyReport(employee, LocalDate.of(2026, 8, 1), 6, 480, 480);

        assertThat(arrivals(employee)).isZero();
    }
}
