package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee.Employee;
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
 * The "šef sektora" compensation scheme, end to end through the REAL daily
 * recalculation.
 *
 * <p>The scheme (migration V41) makes two things true, and both are asserted
 * where they actually happen rather than from a formula in isolation — the same
 * pipeline {@link ProbationRecalcIT} exercises:
 * <ul>
 *   <li>work is <b>credited at 100 %</b> ({@code credits_full_performance}) while
 *       the measured rate is still recorded — like probation, but permanent and
 *       from the scheme, so {@code was_probation} stays FALSE;</li>
 *   <li><b>no shift bonus fires</b> ({@code allows_shift_bonuses = false}) — the
 *       weekend remap does not, even in a week that qualifies for it.</li>
 * </ul>
 *
 * <p>The monthly bonus is untouched by this scheme and is covered by the existing
 * monthly-bonus tests; it is not re-asserted here.
 */
@Transactional
class SectorChiefRecalcIT extends AbstractIntegrationTest {

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    /** A Saturday and the first working day of its week, so the 180-minute rule cannot interfere. */
    private static final LocalDate SATURDAY = LocalDate.of(2026, 7, 4);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 8);

    // ─── 100 %, and the norm does not move it ─────────────────────────────────

    @Test
    @DisplayName("under the norm is paid at 100 %, the real figure is still recorded, and it is NOT probation")
    void underTheNormIsPaidAtOneHundred() {
        var scenario = fixture.scenario().scheme("SECTOR_CHIEF").build();

        // 300 pieces in 480 minutes is 37.5/h against a norm of 40 — 93.75 %.
        Rates rates = workAndRecalculate(scenario, WEDNESDAY, 40, 300);

        assertThat(rates.measured())
                .as("the measured efficiency is still recorded")
                .isEqualByComparingTo("93.75");
        assertThat(rates.approved())
                .as("but a sector chief is paid at 100 %")
                .isEqualByComparingTo("100.0000");
        assertThat(rates.wasProbation())
                .as("100 % here comes from the SCHEME, not probation")
                .isFalse();
    }

    @Test
    @DisplayName("over the norm is also paid at 100 % — it is not a floor")
    void overTheNormIsAlsoPaidAtOneHundred() {
        var scenario = fixture.scenario().scheme("SECTOR_CHIEF").build();

        // 400 pieces in 480 minutes is 50/h against a norm of 40 — 125 %.
        Rates rates = workAndRecalculate(scenario, WEDNESDAY, 40, 400);

        assertThat(rates.measured()).isEqualByComparingTo("125.00");
        assertThat(rates.approved())
                .as("the scheme moves the figure DOWN as well as up")
                .isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("a STANDARD employee doing the same work is paid what it measured — the difference is the scheme")
    void standardEmployeeIsUnchanged() {
        var scenario = fixture.scenario().build(); // STANDARD

        Rates rates = workAndRecalculate(scenario, WEDNESDAY, 40, 300);

        assertThat(rates.measured()).isEqualByComparingTo("93.75");
        assertThat(rates.approved())
                .as("identical to the sector-chief case in every respect except this")
                .isEqualByComparingTo("93.7500");
    }

    // ─── no shift bonus ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a Saturday in a qualifying week keeps its source category — no weekend remap")
    void weekendBonusIsWithheld() {
        var scenario = fixture.scenario().scheme("SECTOR_CHIEF").build();

        List<String> categories = categoriesAfterQualifyingWeek(scenario, SATURDAY);

        assertThat(categories)
                .as("allows_shift_bonuses = false, so the weekend remap never fires")
                .containsExactly(scenario.workCategory().getCategoryNo());
        assertThat(categories).doesNotContain(expectedBonusCategoryNo);
    }

    // ─── helpers (mirroring ProbationRecalcIT) ────────────────────────────────

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

    private List<String> categoriesAfterQualifyingWeek(PayrollScenarioFixture.Scenario scenario,
                                                       LocalDate workDate) {
        Employee employee = scenario.employee();
        WorkCodeCategory category = scenario.workCategory();
        WorkCodeCategory bonus = fixture.bonusMapping(category, "WEEKEND_BONUS");
        expectedBonusCategoryNo = bonus.getCategoryNo();
        Operation operation = fixture.operation(category, 40);

        // A full qualifying week — every calendar day from Monday has ≥ 180
        // bonus-eligible minutes — so for a STANDARD employee the remap WOULD fire.
        // That is what makes this prove the scheme is the reason it does not.
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

    /** Set by categoriesAfterQualifyingWeek; the remap target it built for this test. */
    private String expectedBonusCategoryNo;
}
