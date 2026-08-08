package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employment_period.EmployeeEmploymentPeriodRepository;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probation, end to end through the REAL daily recalculation.
 *
 * <p>Two rules meet here, and until the fixture could record work logs neither
 * could be tested where it actually happens:
 * <ul>
 *   <li>work done on probation is <b>credited at 100 %</b> while the measured rate
 *       is still recorded;</li>
 *   <li>the <b>weekend bonus remap does not fire</b> during probation.</li>
 * </ul>
 *
 * <p>Everything goes through {@code processJob}, so what is asserted is what
 * reaches {@code daily_reports} — not what a formula returns in isolation.
 *
 * <p>{@code @Transactional}, and it works because the recalculation's
 * {@code TransactionTemplate} JOINS the caller's transaction rather than opening
 * its own. Without it every test here would commit into the container the whole
 * suite shares — and it did: schemes other tests create could no longer be
 * activated, because the adjustment catalogue this fixture leaves behind made
 * their rule matrix incomplete.
 *
 * <p>That only became possible once {@code app.recalc.enabled=false} stopped the
 * background workers from claiming these jobs; a worker on another thread cannot
 * see an uncommitted transaction.
 */
@Transactional
class ProbationRecalcIT extends AbstractIntegrationTest {

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EmployeeEmploymentPeriodRepository periodRepository;
    @Autowired private EntityManager entityManager;

    /** A Saturday, and the first working day of its week, so the 180-minute rule cannot interfere. */
    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 4);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 8);

    // ─── 100 % while on probation ───────────────────────────────────────────

    @Test
    @DisplayName("under the norm on probation is paid at 100 %, and the real figure is still recorded")
    void underTheNormIsPaidAtOneHundred() {
        var scenario = onProbation(WEDNESDAY);

        // 300 pieces in 480 minutes is 37.5/h against a norm of 40 — 93.75 %.
        Rates rates = workAndRecalculate(scenario, WEDNESDAY, 40, 300);

        assertThat(rates.measured())
                .as("the payslip must still show what actually happened")
                .isEqualByComparingTo("93.75");
        assertThat(rates.approved())
                .as("but probation is paid at 100 %")
                .isEqualByComparingTo("100.0000");
        assertThat(rates.wasProbation()).isTrue();
    }

    @Test
    @DisplayName("over the norm on probation is also paid at 100 %")
    void overTheNormIsAlsoPaidAtOneHundred() {
        var scenario = onProbation(WEDNESDAY);

        // 400 pieces in 480 minutes is 50/h against a norm of 40 — 125 %.
        Rates rates = workAndRecalculate(scenario, WEDNESDAY, 40, 400);

        assertThat(rates.measured()).isEqualByComparingTo("125.00");
        assertThat(rates.approved())
                .as("probation moves the figure DOWN as well as up — it is not a floor")
                .isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("the same work after probation is paid at what it measured")
    void afterProbationNothingIsChanged() {
        var scenario = offProbation(WEDNESDAY);

        Rates rates = workAndRecalculate(scenario, WEDNESDAY, 40, 300);

        assertThat(rates.measured()).isEqualByComparingTo("93.75");
        assertThat(rates.approved())
                .as("identical to the probation case in every respect except this")
                .isEqualByComparingTo("93.7500");
        assertThat(rates.wasProbation()).isFalse();
    }

    // ─── the weekend bonus ──────────────────────────────────────────────────

    @Test
    @DisplayName("a Saturday worked ON probation keeps its source category — no weekend remap")
    void weekendBonusIsWithheldOnProbation() {
        var scenario = onProbation(SATURDAY);

        List<String> categories = categoriesAfterRecalculating(scenario, SATURDAY);

        assertThat(categories)
                .as("the source category must not be remapped while on probation")
                .containsExactly(scenario.workCategory().getCategoryNo());
        assertThat(categories).doesNotContain(expectedBonusCategoryNo);
    }

    @Test
    @DisplayName("the same Saturday worked after probation becomes JB")
    void weekendBonusAppliesAfterProbation() {
        var scenario = offProbation(SATURDAY);

        List<String> categories = categoriesAfterRecalculating(scenario, SATURDAY);

        assertThat(categories)
                .as("the weekend remap is the ordinary behaviour and must be intact")
                .containsExactly(expectedBonusCategoryNo);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private PayrollScenarioFixture.Scenario onProbation(LocalDate workDate) {
        return scenarioWithPeriod(workDate.minusDays(5), 30);
    }

    private PayrollScenarioFixture.Scenario offProbation(LocalDate workDate) {
        return scenarioWithPeriod(workDate.minusDays(200), 30);
    }

    /**
     * @return the scenario, whose own work category is used — the production
     *   categories (J, JB …) are seeded by a migration the test schema folds into
     *   the baseline as DDL only, so they do not exist here.
     */
    private PayrollScenarioFixture.Scenario scenarioWithPeriod(LocalDate startedOn, int graceDays) {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        periodRepository.findLatestOne(scenario.employee().getId()).ifPresent(period -> {
            period.setStartedOn(startedOn);
            period.setNormGraceDays(graceDays);
            periodRepository.saveAndFlush(period);
        });
        return scenario;
    }

    /** Records one 8-hour stretch of work and runs the real recalculation. */
    private Rates workAndRecalculate(PayrollScenarioFixture.Scenario scenario, LocalDate workDate,
                                     int minNorm, int quantity) {
        Employee employee = scenario.employee();
        WorkCodeCategory category = scenario.workCategory();
        Operation operation = fixture.operation(category, minNorm);
        WorkShift shift = fixture.workShift(employee, workDate, 6, 480);

        fixture.workLog(shift, operation, category, 0, 480, quantity);
        fixture.recalculate(shift);

        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT performance_rate, approved_performance_rate, was_probation
                FROM daily_reports WHERE employee_id = :e AND work_date = :d
                """)
                .setParameter("e", employee.getId())
                .setParameter("d", workDate)
                .getSingleResult();

        return new Rates((BigDecimal) row[0], (BigDecimal) row[1], (Boolean) row[2]);
    }

    private List<String> categoriesAfterRecalculating(PayrollScenarioFixture.Scenario scenario,
                                                      LocalDate workDate) {
        Employee employee = scenario.employee();
        WorkCodeCategory category = scenario.workCategory();
        // The weekend remap this test is about; the production J -> JB mapping is
        // not in the test schema, so the pair is built here.
        WorkCodeCategory bonus = fixture.bonusMapping(category, "WEEKEND_BONUS");
        expectedBonusCategoryNo = bonus.getCategoryNo();
        Operation operation = fixture.operation(category, 40);

        // THE WEEKEND BONUS NEEDS A QUALIFYING WEEK. isWeekendBonusEligible counts
        // every CALENDAR day from Monday up to this one — generate_series, not the
        // days that happen to have a report — and refuses the bonus if any of them
        // has under 180 bonus-eligible minutes. Recording only the Saturday leaves
        // five empty days and the remap never fires, whatever probation says.
        for (LocalDate day = workDate.with(java.time.DayOfWeek.MONDAY);
             day.isBefore(workDate); day = day.plusDays(1)) {
            WorkShift weekday = fixture.workShift(employee, day, 6, 480);
            fixture.workLog(weekday, operation, category, 0, 480, 300);
            fixture.recalculate(weekday);
        }

        WorkShift shift = fixture.workShift(employee, workDate, 6, 480);
        fixture.workLog(shift, operation, category, 0, 480, 300);
        fixture.recalculate(shift);

        @SuppressWarnings("unchecked")
        List<String> categories = entityManager.createNativeQuery("""
                SELECT c.category_no
                FROM daily_report_categories drc
                JOIN daily_reports dr ON dr.id = drc.daily_report_id
                JOIN work_code_categories c ON c.id = drc.work_code_category_id
                WHERE dr.employee_id = :e AND dr.work_date = :d
                ORDER BY c.category_no
                """)
                .setParameter("e", employee.getId())
                .setParameter("d", workDate)
                .getResultList();
        return categories;
    }

    private record Rates(BigDecimal measured, BigDecimal approved, Boolean wasProbation) {}

    /** Set by categoriesAfterRecalculating; the remap target it built for this test. */
    private String expectedBonusCategoryNo;
}
