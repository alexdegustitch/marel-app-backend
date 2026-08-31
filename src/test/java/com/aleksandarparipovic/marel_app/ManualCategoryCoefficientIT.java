package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
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
 * A coefficient typed over the resolved one, through the REAL recalculation.
 *
 * <p>The arithmetic this is about, in the terms it was asked in: an operation
 * worked for {@code t} minutes at efficiency {@code x}, in a category whose
 * coefficient is {@code y}, is worth {@code t · x · y}. Typing {@code y1} over
 * {@code y} must make it worth {@code t · x · y1} — no more, and by a route that
 * still shows both numbers afterwards.
 *
 * <p>The row is what carries it. A category is no longer one row of a daily
 * report: it is one row PER COEFFICIENT, so four hours of the same category can
 * be two at 1.10 and two at 1.20 without either being averaged into a number
 * nobody entered.
 *
 * <p>{@code @Transactional} for the same reason as ProbationRecalcIT: the
 * recalculation joins the caller's transaction, so nothing here is committed into
 * the container the whole suite shares.
 */
@Transactional
class ManualCategoryCoefficientIT extends AbstractIntegrationTest {

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private WorkLogRepository workLogRepository;
    @Autowired private com.aleksandarparipovic.marel_app.user.UserRepository userRepository;
    @Autowired private com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository workShiftRepository;
    @Autowired private com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategoryRepository monthlyReportCategoryRepository;
    @Autowired private com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemService payrollRunItemService;
    @Autowired private com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository payrollRunItemRepository;
    @Autowired private com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;
    @Autowired private EntityManager entityManager;

    /** A plain Wednesday: no weekend remap, no bonus week to qualify for. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 8);

    // ─── the row splits ─────────────────────────────────────────────────────

    @Test
    @DisplayName("one category worked at two coefficients becomes two rows")
    void twoCoefficientsAreTwoRows() {
        PayrollScenarioFixture.Scenario scenario = scenarioAt(1.10);
        WorkCodeCategory category = scenario.workCategory();
        Operation operation = fixture.operation(category, 40);
        WorkShift shift = fixture.workShift(scenario.employee(), WEDNESDAY, 6, 480);

        // Two hours left alone, two hours typed over.
        fixture.workLog(shift, operation, category, 0, 120, 180);
        typeCoefficient(fixture.workLog(shift, operation, category, 120, 120, 180), "1.20");

        fixture.recalculate(shift);

        assertThat(rows(scenario.employee()))
                .as("the resolved coefficient and the typed one, side by side")
                .containsExactly(new Row("1.10", 120), new Row("1.20", 120));
    }

    @Test
    @DisplayName("untouched work stays one row")
    void withoutAnOverrideNothingSplits() {
        PayrollScenarioFixture.Scenario scenario = scenarioAt(1.10);
        WorkCodeCategory category = scenario.workCategory();
        Operation operation = fixture.operation(category, 40);
        WorkShift shift = fixture.workShift(scenario.employee(), WEDNESDAY, 6, 480);

        fixture.workLog(shift, operation, category, 0, 120, 180);
        fixture.workLog(shift, operation, category, 120, 120, 180);

        fixture.recalculate(shift);

        assertThat(rows(scenario.employee()))
                .containsExactly(new Row("1.10", 240));
    }

    // ─── the arithmetic ─────────────────────────────────────────────────────

    @Test
    @DisplayName("t x y1: four hours at 100 % under a typed 1.20 is worth 288 minutes")
    void theWorkIsWorthTheTypedCoefficient() {
        PayrollScenarioFixture.Scenario scenario = scenarioAt(1.10);
        WorkCodeCategory category = scenario.workCategory();

        // One minute of norm per piece: 240 pieces in 240 minutes is exactly
        // 100 %, which is what makes the arithmetic below readable as t x 1 x y1.
        Operation operation = fixture.operation(category, 1);
        WorkShift shift = fixture.workShift(scenario.employee(), WEDNESDAY, 6, 480);

        typeCoefficient(fixture.workLog(shift, operation, category, 0, 240, 240), "1.20");
        fixture.recalculate(shift);

        List<Row> rows = rows(scenario.employee());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().coefficient()).isEqualTo("1.20");

        // t · x, the efficiency-weighted minutes, unchanged by the coefficient.
        assertThat(weightedMinutes(scenario.employee())).isEqualByComparingTo("240.0000");

        // t · x · y1. The coefficient meets the minutes once, at the payroll.
        assertThat(weightedMinutes(scenario.employee())
                .multiply(new BigDecimal(rows.getFirst().coefficient())))
                .as("240 x 1.20")
                .isEqualByComparingTo("288.00");
    }

    @Test
    @DisplayName("the bonus-eligible minutes move with it")
    void bonusEligibleMinutesFollowTheTypedCoefficient() {
        PayrollScenarioFixture.Scenario scenario = scenarioAt(1.10);
        WorkCodeCategory category = scenario.workCategory();
        Operation operation = fixture.operation(category, 1);
        WorkShift shift = fixture.workShift(scenario.employee(), WEDNESDAY, 6, 480);

        typeCoefficient(fixture.workLog(shift, operation, category, 0, 240, 240), "1.20");
        fixture.recalculate(shift);

        // 240 x 1.20, not 240 x 1.10. Pay and bonus read the same number.
        assertThat(bonusEligibleMinutes(scenario.employee())).isEqualTo(288);
    }

    @Test
    @DisplayName("the verified hours move with it too, without being told to")
    void verifiedMinutesFollowTheTypedCoefficient() {
        PayrollScenarioFixture.Scenario scenario = scenarioAt(1.10);
        WorkCodeCategory category = scenario.workCategory();
        Operation operation = fixture.operation(category, 1);
        WorkShift shift = fixture.workShift(scenario.employee(), WEDNESDAY, 6, 480);

        typeCoefficient(fixture.workLog(shift, operation, category, 0, 240, 240), "1.20");
        fixture.recalculate(shift);

        // The interval engine reads the log's effective coefficient, so covered
        // time is weighted by 1.20 with no code path of its own.
        assertThat(verifiedMinutes(scenario.employee())).isEqualByComparingTo("288.0000");
    }

    // ─── taking it back ─────────────────────────────────────────────────────

    @Test
    @DisplayName("clearing it puts the work back on the category's own coefficient")
    void clearingReturnsToTheResolvedCoefficient() {
        PayrollScenarioFixture.Scenario scenario = scenarioAt(1.10);
        WorkCodeCategory category = scenario.workCategory();
        Operation operation = fixture.operation(category, 1);
        WorkShift shift = fixture.workShift(scenario.employee(), WEDNESDAY, 6, 480);

        WorkLog log = fixture.workLog(shift, operation, category, 0, 240, 240);
        typeCoefficient(log, "1.20");
        fixture.recalculate(shift);
        assertThat(rows(scenario.employee()).getFirst().coefficient()).isEqualTo("1.20");

        typeCoefficient(reload(log), null);
        recalculateAgain(shift);

        assertThat(rows(scenario.employee()))
                .as("back to one row, at what the scheme resolves")
                .containsExactly(new Row("1.10", 240));
    }

    @Test
    @DisplayName("a recalculation does not erase what somebody typed")
    void recalculationLeavesTheTypedValueAlone() {
        PayrollScenarioFixture.Scenario scenario = scenarioAt(1.10);
        WorkCodeCategory category = scenario.workCategory();
        Operation operation = fixture.operation(category, 1);
        WorkShift shift = fixture.workShift(scenario.employee(), WEDNESDAY, 6, 480);

        WorkLog log = fixture.workLog(shift, operation, category, 0, 240, 240);
        typeCoefficient(log, "1.20");

        fixture.recalculate(shift);
        recalculateAgain(shift);
        recalculateAgain(shift);

        WorkLog reloaded = reload(log);

        assertThat(reloaded.getNormMultiplierManual())
                .as("the typed value survives; the snapshot beside it is the resolved one")
                .isEqualByComparingTo("1.20");
        assertThat(reloaded.getNormMultiplierSnapshot())
                .as("still the default, so the screen can say what was departed from")
                .isEqualByComparingTo("1.10");
    }

    // ─── and through to the payroll ─────────────────────────────────────────

    @Test
    @DisplayName("both coefficients reach the payroll, and no minutes are lost on the way")
    void bothCoefficientsReachThePayroll() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario()
                .normMultiplier(1.10)
                .workMinutes(240)
                .weightedNormMinutes("120")
                .build();

        // The month as a split row set: half the work at the category's own
        // coefficient, half at one somebody typed. This is what the recalculation
        // engine now writes, and what the payroll has to read.
        setMonthRowCoefficient(scenario, "1.10");
        monthlyReportCategoryRepository.saveAndFlush(MonthlyReportCategory.builder()
                .monthlyReport(scenario.monthlyReport())
                .workCodeCategory(scenario.workCategory())
                .totalMinutes(120)
                .totalPaidMinutes(120)
                .totalQuantity(0)
                .totalScrap(0)
                .totalWeightedNormMinutes(new java.math.BigDecimal("120"))
                .totalApprovedMinutes(new java.math.BigDecimal("120"))
                .normMultiplier(new BigDecimal("1.20"))
                .normMultiplierDefault(new BigDecimal("1.10"))
                .sourceType("WORK")
                .createdAt(java.time.OffsetDateTime.now())
                .build());

        PayrollRunItem item = payrollRunItemRepository.findById(scenario.item().getId()).orElseThrow();
        item.setNeedsRecalculation(true);
        payrollRunItemRepository.saveAndFlush(item);

        payrollRunItemService.getForPayrollAccess(scenario.item().getId());
        entityManager.flush();

        List<PayrollRunItemCategory> rows = payrollRunItemCategoryRepository
                .findByPayrollRunItemIdWithWorkCodeCategory(scenario.item().getId()).stream()
                .filter(c -> c.getWorkCodeCategory().getId().equals(scenario.workCategory().getId()))
                .sorted(java.util.Comparator.comparing(PayrollRunItemCategory::getCategoryCoefficientSnapshot))
                .toList();

        assertThat(rows)
                .as("one payroll line per coefficient — keyed on the category alone,"
                        + " the second month row was dropped and its minutes never paid")
                .hasSize(2);
        assertThat(rows.get(0).getCategoryCoefficientSnapshot()).isEqualByComparingTo("1.10");
        assertThat(rows.get(1).getCategoryCoefficientSnapshot()).isEqualByComparingTo("1.20");

        // 120 x 1.10 + 120 x 1.20 = 276, and every minute of it is on the payroll.
        assertThat(rows.stream()
                .map(PayrollRunItemCategory::getEffectiveMinutes)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("276.00");

        assertThat(rows.stream().mapToInt(PayrollRunItemCategory::getTotalMinutes).sum())
                .as("240 minutes were worked and 240 are accounted for")
                .isEqualTo(240);
    }

    @Test
    @DisplayName("a line at a withdrawn coefficient is removed, not left at zero")
    void spentOverrideLineIsRemoved() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario()
                .normMultiplier(1.10)
                .workMinutes(240)
                .weightedNormMinutes("120")
                .build();

        setMonthRowCoefficient(scenario, "1.10");
        MonthlyReportCategory overridden = monthlyReportCategoryRepository.saveAndFlush(
                MonthlyReportCategory.builder()
                        .monthlyReport(scenario.monthlyReport())
                        .workCodeCategory(scenario.workCategory())
                        .totalMinutes(120)
                        .totalPaidMinutes(120)
                        .totalQuantity(0)
                        .totalScrap(0)
                        .totalWeightedNormMinutes(new BigDecimal("120"))
                        .totalApprovedMinutes(new BigDecimal("120"))
                        .normMultiplier(new BigDecimal("1.20"))
                        .normMultiplierDefault(new BigDecimal("1.10"))
                        .sourceType("WORK")
                        .createdAt(java.time.OffsetDateTime.now())
                        .build());

        refreshPayroll(scenario);
        assertThat(payrollRows(scenario)).hasSize(2);

        // The supervisor takes the override back: the month no longer holds any
        // work at 1.20.
        monthlyReportCategoryRepository.delete(overridden);
        setMonthRowCoefficient(scenario, "1.10");
        refreshPayroll(scenario);

        List<PayrollRunItemCategory> rows = payrollRows(scenario);
        assertThat(rows)
                .as("the 1.20 line is gone rather than sitting there at 0:00")
                .hasSize(1);
        assertThat(rows.getFirst().getCategoryCoefficientSnapshot()).isEqualByComparingTo("1.10");
    }

    @Test
    @DisplayName("an empty line at the category's own coefficient stays")
    void anEmptyOrdinaryLineIsKept() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario()
                .normMultiplier(1.10)
                .workMinutes(240)
                .weightedNormMinutes("120")
                .build();

        setMonthRowCoefficient(scenario, "1.10");
        refreshPayroll(scenario);
        assertThat(payrollRows(scenario)).hasSize(1);

        // Every minute of the month moves onto a typed coefficient, so the line at
        // the category's own is left with nothing on it.
        MonthlyReportCategory only = monthlyReportCategoryRepository
                .findByMonthlyReportIdWithCategory(scenario.monthlyReport().getId()).getFirst();
        only.setNormMultiplier(new BigDecimal("1.20"));
        only.setNormMultiplierDefault(new BigDecimal("1.10"));
        monthlyReportCategoryRepository.saveAndFlush(only);
        refreshPayroll(scenario);

        List<PayrollRunItemCategory> rows = payrollRows(scenario);
        assertThat(rows)
                .as("the emptied line is kept — \"this category, nothing at its own"
                        + " coefficient\" is an answer somebody may want")
                .hasSize(2);
        assertThat(rows.getFirst().getCategoryCoefficientSnapshot()).isEqualByComparingTo("1.10");
        assertThat(rows.getFirst().getEffectiveMinutes()).isEqualByComparingTo("0");
        assertThat(rows.get(1).getCategoryCoefficientSnapshot()).isEqualByComparingTo("1.20");
        assertThat(rows.get(1).getEffectiveMinutes()).isEqualByComparingTo("144.00");
    }

    @Test
    @DisplayName("an override line still present in the month, but empty, is removed too")
    void anEmptiedOverrideLineIsRemovedEvenWhenTheMonthStillHasIt() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario()
                .normMultiplier(1.10)
                .workMinutes(240)
                .weightedNormMinutes("120")
                .build();

        setMonthRowCoefficient(scenario, "1.10");
        MonthlyReportCategory overridden = monthlyReportCategoryRepository.saveAndFlush(
                monthRow(scenario, "1.20", 120, "120"));

        refreshPayroll(scenario);
        assertThat(payrollRows(scenario)).hasSize(2);

        /*
         * The month KEEPS the 1.20 row but it empties out. This is the case the
         * first rule missed: it only looked for a coefficient the month no longer
         * held at all, so a row that stayed behind at zero was populated with
         * zeroes and left on the payroll.
         */
        overridden.setTotalMinutes(0);
        overridden.setTotalPaidMinutes(0);
        overridden.setTotalWeightedNormMinutes(BigDecimal.ZERO);
        monthlyReportCategoryRepository.saveAndFlush(overridden);
        refreshPayroll(scenario);

        assertThat(payrollRows(scenario))
                .as("nothing is worked at 1.20 any more, so the line goes")
                .hasSize(1);
        assertThat(payrollRows(scenario).getFirst().getCategoryCoefficientSnapshot())
                .isEqualByComparingTo("1.10");
    }

    @Test
    @DisplayName("the category's own coefficient comes first, then the typed ones ascending")
    void linesAreOrderedOriginalFirstThenAscending() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario()
                .normMultiplier(1.10)
                .workMinutes(240)
                .weightedNormMinutes("60")
                .build();

        // Deliberately written highest-first, so a passing assertion cannot be
        // insertion order wearing a sort's clothes. Every row DEPARTS FROM 1.10 —
        // the row at 1.10 is therefore the category's own and the rest are typed.
        setMonthRow(scenario, "1.30", "1.10");
        monthlyReportCategoryRepository.saveAndFlush(monthRow(scenario, "1.20", 60, "60"));
        monthlyReportCategoryRepository.saveAndFlush(monthRow(scenario, "1.10", 60, "60"));
        monthlyReportCategoryRepository.saveAndFlush(monthRow(scenario, "0.90", 60, "60"));

        refreshPayroll(scenario);

        assertThat(payrollRows(scenario, false).stream()
                .map(c -> c.getCategoryCoefficientSnapshot().setScale(2, java.math.RoundingMode.HALF_UP)
                        .toPlainString())
                .toList())
                .as("1.10 is the category's own and leads; the rest follow in order")
                .containsExactly("1.10", "0.90", "1.20", "1.30");
    }

    /** A month row for the scenario's category at one coefficient. */
    private MonthlyReportCategory monthRow(PayrollScenarioFixture.Scenario scenario,
                                           String coefficient, int minutes, String weighted) {
        return MonthlyReportCategory.builder()
                .monthlyReport(scenario.monthlyReport())
                .workCodeCategory(scenario.workCategory())
                .totalMinutes(minutes)
                .totalPaidMinutes(minutes)
                .totalQuantity(0)
                .totalScrap(0)
                .totalWeightedNormMinutes(new BigDecimal(weighted))
                .totalApprovedMinutes(new BigDecimal(weighted))
                .normMultiplier(new BigDecimal(coefficient))
                .normMultiplierDefault(new BigDecimal("1.10"))
                .sourceType("WORK")
                .createdAt(java.time.OffsetDateTime.now())
                .build();
    }

    /** Force the item stale and read it back through the real access path. */
    private void refreshPayroll(PayrollScenarioFixture.Scenario scenario) {
        PayrollRunItem item = payrollRunItemRepository.findById(scenario.item().getId()).orElseThrow();
        item.setNeedsRecalculation(true);
        payrollRunItemRepository.saveAndFlush(item);
        payrollRunItemService.getForPayrollAccess(scenario.item().getId());
        entityManager.flush();
    }

    private List<PayrollRunItemCategory> payrollRows(PayrollScenarioFixture.Scenario scenario) {
        return payrollRows(scenario, true);
    }

    /**
     * @param sorted true to sort by coefficient here, which most assertions want;
     *               false to read the order the QUERY returns, which is the thing
     *               the ordering test is about
     */
    private List<PayrollRunItemCategory> payrollRows(PayrollScenarioFixture.Scenario scenario,
                                                     boolean sorted) {
        var rows = payrollRunItemCategoryRepository
                .findByPayrollRunItemIdWithWorkCodeCategory(scenario.item().getId()).stream()
                .filter(c -> c.getWorkCodeCategory().getId().equals(scenario.workCategory().getId()));
        return sorted
                ? rows.sorted(java.util.Comparator.comparing(
                        PayrollRunItemCategory::getCategoryCoefficientSnapshot)).toList()
                : rows.toList();
    }

    /** The month row the fixture created stands for the un-overridden half. */
    private void setMonthRowCoefficient(PayrollScenarioFixture.Scenario scenario, String coefficient) {
        setMonthRow(scenario, coefficient, coefficient);
    }

    /**
     * The fixture's month row, at a coefficient and the one it departs from.
     *
     * <p>Passing them separately matters: equal means the row is the category's
     * own, different means somebody typed it, and a helper that quietly set both
     * to the same number made an override look ordinary — which is how the
     * ordering test first "failed" against correct code.
     */
    private void setMonthRow(PayrollScenarioFixture.Scenario scenario,
                             String coefficient, String departsFrom) {
        MonthlyReportCategory existing = monthlyReportCategoryRepository
                .findByMonthlyReportIdWithCategory(scenario.monthlyReport().getId())
                .getFirst();
        existing.setTotalMinutes(120);
        existing.setTotalPaidMinutes(120);
        existing.setNormMultiplier(new BigDecimal(coefficient));
        existing.setNormMultiplierDefault(new BigDecimal(departsFrom));
        monthlyReportCategoryRepository.saveAndFlush(existing);
    }

    // ─── fixtures and readings ──────────────────────────────────────────────

    private PayrollScenarioFixture.Scenario scenarioAt(double normMultiplier) {
        return fixture.scenario().normMultiplier(normMultiplier).build();
    }

    /**
     * Run the daily recalculation a SECOND time on the same shift.
     *
     * <p>The clear is the whole point. This test holds one transaction and so one
     * persistence context, while the queue is claimed with a bulk UPDATE that the
     * context knows nothing about — so the second {@code processJob} would read
     * the queue row it already has cached, see the status the first run left, and
     * quietly return without recomputing anything. In production each job runs in
     * a context of its own and the question does not arise.
     */
    private void recalculateAgain(WorkShift shift) {
        entityManager.flush();
        entityManager.clear();
        fixture.recalculate(workShiftRepository.findById(shift.getId()).orElseThrow());
    }

    private WorkLog reload(WorkLog log) {
        entityManager.flush();
        entityManager.clear();
        return workLogRepository.findById(log.getId()).orElseThrow();
    }

    /** Sets the coefficient the way the mapper does, without going through HTTP. */
    private void typeCoefficient(WorkLog log, String coefficient) {
        log.setNormMultiplierManual(coefficient == null ? null : new BigDecimal(coefficient));
        if (coefficient == null) {
            log.setNormMultiplierManualBy(null);
            log.setNormMultiplierManualAt(null);
        } else {
            log.setNormMultiplierManualBy(userRepository.findAll().getFirst());
            log.setNormMultiplierManualAt(java.time.OffsetDateTime.now());
        }
        workLogRepository.saveAndFlush(log);
    }

    /** The day's category rows, in coefficient order, as the report holds them. */
    private List<Row> rows(Employee employee) {
        entityManager.flush();
        @SuppressWarnings("unchecked")
        List<Object[]> raw = entityManager.createNativeQuery("""
                SELECT drc.norm_multiplier, drc.total_minutes
                FROM daily_report_categories drc
                JOIN daily_reports dr ON dr.id = drc.daily_report_id
                WHERE dr.employee_id = :e AND dr.work_date = :d
                ORDER BY drc.norm_multiplier
                """)
                .setParameter("e", employee.getId())
                .setParameter("d", WEDNESDAY)
                .getResultList();
        return raw.stream()
                .map(r -> new Row(
                        ((BigDecimal) r[0]).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                        ((Number) r[1]).intValue()))
                .toList();
    }

    private BigDecimal weightedMinutes(Employee employee) {
        entityManager.flush();
        return (BigDecimal) entityManager.createNativeQuery("""
                SELECT total_weighted_norm_minutes FROM daily_reports
                WHERE employee_id = :e AND work_date = :d
                """)
                .setParameter("e", employee.getId())
                .setParameter("d", WEDNESDAY)
                .getSingleResult();
    }

    private int bonusEligibleMinutes(Employee employee) {
        entityManager.flush();
        return ((Number) entityManager.createNativeQuery("""
                SELECT bonus_eligible_minutes FROM daily_reports
                WHERE employee_id = :e AND work_date = :d
                """)
                .setParameter("e", employee.getId())
                .setParameter("d", WEDNESDAY)
                .getSingleResult()).intValue();
    }

    private BigDecimal verifiedMinutes(Employee employee) {
        entityManager.flush();
        return (BigDecimal) entityManager.createNativeQuery("""
                SELECT total_verified_minutes FROM daily_reports
                WHERE employee_id = :e AND work_date = :d
                """)
                .setParameter("e", employee.getId())
                .setParameter("d", WEDNESDAY)
                .getSingleResult();
    }

    /**
     * The coefficient as TEXT at scale two.
     *
     * <p>{@code containsExactly} compares with equals, and BigDecimal's equals is
     * scale-sensitive: 1.1 and 1.10 are different objects and the same number.
     * Comparing the rendered value sidesteps a failure that would be about
     * arithmetic bookkeeping rather than about the row.
     */
    private record Row(String coefficient, int minutes) {}
}
