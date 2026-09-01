package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.report_worker.MonthlyRecalcService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which minutes count for which bonus is the CATEGORY's answer now.
 *
 * <p>It used to be {@code type = 'WORK'}, decided in Java in two places and
 * unchangeable without a release — and the same condition served both bonuses,
 * although they ask different questions of different numbers. The weekend one
 * asks whether every day of the week reached its minutes; the monthly one how
 * many hours the month came to.
 *
 * <p>Both flags were backfilled from exactly the old condition, so what is under
 * test here is that they are READ, and that turning one off stops those minutes
 * counting for that bonus and no other. Whether the backfill preserved every
 * existing figure is {@code PayrollGoldenSnapshotIT}'s job.
 */
@Transactional
class BonusFlagsDecideWhichMinutesCountIT extends AbstractIntegrationTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 8);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 8, 19);
    private static final int FULL_SHIFT = 480;

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private MonthlyReportRepository monthlyReportRepository;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private RecalcQueueService recalcQueueService;
    @Autowired private MonthlyRecalcService monthlyRecalcService;
    @Autowired private EntityManager entityManager;

    private Employee employee;
    private WorkCodeCategory workCategory;
    private Operation workOperation;

    @BeforeEach
    void setUp() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(MONTH).build();
        employee = scenario.employee();
        workCategory = scenario.workCategory();
        workOperation = fixture.operation(workCategory, 10);
    }

    private WorkShift workedShift() {
        WorkShift shift = fixture.workShift(employee, WORK_DATE, 6, FULL_SHIFT);
        fixture.workLog(shift, workOperation, workCategory, 0, FULL_SHIFT, 800);
        return shift;
    }

    private int bonusEligibleMinutesAfterRecalc(WorkShift shift) {
        fixture.recalculate(shift);
        DailyReport report = dailyReportRepository.findByWorkShiftId(shift.getId()).orElseThrow();
        return report.getBonusEligibleMinutes();
    }

    private int monthlyBonusMinutesAfterRecalc() {
        recalcQueueService.enqueueMonthlyJob(employee, MONTH.getYear(), MONTH.getMonthValue(), "test");
        List<Long> claimed = recalcQueueService.claimMonthlyJobIds(50, "test");
        claimed.forEach(monthlyRecalcService::processJob);

        entityManager.flush();
        entityManager.clear();
        MonthlyReport report = monthlyReportRepository
                .findByEmployeeIdAndEmployeeRecordStartDate(employee.getId(), MONTH.atDay(1))
                .orElseThrow();
        return report.getMonthlyBonusEligibleMinutes();
    }

    @Test
    @DisplayName("a category that affects the weekend bonus puts its minutes towards the day's 180")
    void weekendFlagOnCounts() {
        assertThat(bonusEligibleMinutesAfterRecalc(workedShift())).isEqualTo(FULL_SHIFT);
    }

    @Test
    @DisplayName("and turning that flag off takes them out, without touching the work total")
    void weekendFlagOffStopsCounting() {
        workCategory.setAffectsWeekendBonus(false);
        categoryRepository.saveAndFlush(workCategory);

        WorkShift shift = workedShift();
        assertThat(bonusEligibleMinutesAfterRecalc(shift)).isZero();

        // The work itself is untouched: the flag decides what counts for a BONUS,
        // not what was worked or what is paid.
        DailyReport report = dailyReportRepository.findByWorkShiftId(shift.getId()).orElseThrow();
        assertThat(report.getTotalWorkMinutes()).isEqualTo(FULL_SHIFT);
    }

    @Test
    @DisplayName("a category that affects the monthly bonus puts its minutes towards the month's hours")
    void monthlyFlagOnCounts() {
        fixture.recalculate(workedShift());

        assertThat(monthlyBonusMinutesAfterRecalc()).isEqualTo(FULL_SHIFT);
    }

    @Test
    @DisplayName("and turning it off takes them out of the month's hours")
    void monthlyFlagOffStopsCounting() {
        workCategory.setAffectsMonthlyBonus(false);
        categoryRepository.saveAndFlush(workCategory);

        fixture.recalculate(workedShift());

        assertThat(monthlyBonusMinutesAfterRecalc()).isZero();
    }

    @Test
    @DisplayName("and the two flags are independent — off for the weekend is still on for the month")
    void theFlagsAreIndependent() {
        workCategory.setAffectsWeekendBonus(false);
        categoryRepository.saveAndFlush(workCategory);

        assertThat(bonusEligibleMinutesAfterRecalc(workedShift())).isZero();
        assertThat(monthlyBonusMinutesAfterRecalc()).isEqualTo(FULL_SHIFT);
    }
}
