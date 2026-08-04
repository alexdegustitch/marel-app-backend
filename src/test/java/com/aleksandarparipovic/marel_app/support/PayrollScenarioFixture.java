package com.aleksandarparipovic.marel_app.support;

import com.aleksandarparipovic.marel_app.app_settings.AppSetting;
import com.aleksandarparipovic.marel_app.bonus.BonusCategory;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRule;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRule;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonus;
import com.aleksandarparipovic.marel_app.app_settings.AppSettingRepository;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.repository.EmployeeRecordRepository;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReportRepository;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategory;
import com.aleksandarparipovic.marel_app.monthly_report_category.MonthlyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustmentRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRule;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRuleRepository;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRun;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRunRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategoryRepository;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds a complete, calculable payroll month for one employee.
 *
 * <p>Exists because the golden snapshot has to pin the payroll layer against
 * REAL data, and that layer sits at the end of a long chain: employee → record →
 * monthly report → monthly report categories → payroll run → run item → item
 * categories → adjustment lines. A test that stubs any link in that chain stops
 * proving anything about the arithmetic being frozen.
 *
 * <p><b>The recalculation pipeline is deliberately NOT driven.</b> The fixture
 * writes {@code monthly_reports} and {@code monthly_report_categories} directly,
 * because the migration this test protects changes the payroll layer and not
 * {@code DailyRecalcService} / {@code MonthlyRecalcService}. Driving the whole
 * pipeline would make the snapshot fail for reasons that have nothing to do with
 * the migration.
 *
 * <p>Two exceptions are built from the bottom up because a rule depends on them:
 * {@link #dailyReport} and {@link #workShift} exist so the transport counting
 * rule (D3) can be pinned against the real {@code daily_reports} shape.
 *
 * <p><b>The adjustment catalogue is created here, not read from a seed.</b> The
 * test schema applies only migrations from {@code 2026-07-21} onward, and the
 * catalogue is seeded by {@code 2026-04-25-payroll-model-restructure.sql}, which
 * is folded into the baseline as DDL only. {@link #catalogue()} therefore
 * recreates the production seed verbatim — see the constant below. If the
 * production seed changes, this constant is what has to change with it.
 */
@Component
@RequiredArgsConstructor
public class PayrollScenarioFixture {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeRecordRepository employeeRecordRepository;
    private final CompensationSchemeRepository schemeRepository;
    private final EmployeeCompensationSchemeHistoryRepository schemeHistoryRepository;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;
    private final WorkCodeCategorySchemeRuleRepository workRuleRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final MonthlyReportCategoryRepository monthlyReportCategoryRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final PayrollRunItemCategoryRepository payrollRunItemCategoryRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final PayrollAdjustmentCategoryRepository adjustmentCategoryRepository;
    private final PayrollAdjustmentCategorySchemeRuleRepository adjustmentRuleRepository;
    private final AppSettingRepository appSettingRepository;
    private final DailyReportRepository dailyReportRepository;
    private final WorkShiftRepository workShiftRepository;
    private final ShiftRepository shiftRepository;
    private final com.aleksandarparipovic.marel_app.bonus.BonusCategoryRepository bonusCategoryRepository;
    private final com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonusRepository employeeBonusRepository;
    private final com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleRepository bonusMinHoursRuleRepository;
    private final com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRuleRepository bonusEligibilityRuleRepository;

    // ── the production adjustment catalogue ─────────────────────────────────
    //
    // MIRRORS PRODUCTION, NOT THE SEED. The catalogue drifted after
    // 2026-04-25-payroll-model-restructure.sql shipped: five categories were
    // moved out of ADDITIONS/SETTLEMENTS into sections of their own, and
    // PAID_PART_2's impact was changed. Verified against Q8 of
    // docs/business-rules/payroll-migration-diagnostics.sql on 2026-07-31.
    //
    // THIS IS NOT COSMETIC. recalculateSummaryTotals routes money by
    // section_code, so a category in section MEAL, PHONE, SETTLEMENTS_SUM or
    // BALANCE reaches NEITHER additionsSum NOR previouslyPaid. Reproducing the
    // seed here instead would pin arithmetic that production does not perform.
    //
    //   ADDITIONS        -> additionsSum -> totalNetEarnings
    //   SETTLEMENTS      -> previouslyPaid -> currentBalance
    //   MEAL             -> nothing (the item column carries the money)
    //   PHONE            -> nothing (deducted next month via PHONE_PREVIOUS_MONTH)
    //   SETTLEMENTS_SUM  -> nothing (display mirror)
    //   BALANCE          -> nothing (previous_net_payable_amount carries it)
    //   impact DEDUCTION_MINUS -> totalDeductionsAmount, which is display only
    //
    // Every column below is a verbatim copy of diagnostic Q12 run on 2026-07-31.
    // Nothing here is inferred or carried over from the seed.
    //
    // code, name, section, sectionOrder, sortOrder, impact, inputType,
    // isManual, allowOverride, overrideTarget, allowNegative, showName, calculationKey,
    // editableInput, allowTotalOverride
    //
    // calculation_key is the value AFTER 2026-08-05-01: TRANSPORT_BY_WORK_DAYS was
    // renamed because D3 changed what it counts, and the three keys that named
    // algorithms nobody ever wrote became MANUAL — which is what the system
    // actually does. Every key here must have a calculator, or the run stops.
    private static final List<Object[]> CATALOGUE = List.of(
            new Object[]{"INSTALLMENT", "Rata", "SETTLEMENTS", 10, 10, "DEDUCTION_MINUS", "AMOUNT", true, true, "AMOUNT", false, true, "MANUAL", "AMOUNT", false},
            new Object[]{"MEAL_ALLOWANCE", "Topli obrok", "MEAL", 10, 10, "GROSS_PLUS", "QTY_X_RATE", false, false, "UNIT_AMOUNT", false, true, "MEAL_BY_ELIGIBLE_SHIFTS", "UNIT_AMOUNT", false},
            new Object[]{"PHONE_CURRENT_MONTH", "Telefon za tekući mesec", "PHONE", 10, 10, "DEDUCTION_MINUS", "AMOUNT", true, true, "AMOUNT", false, true, "MANUAL", "AMOUNT", false},
            new Object[]{"TRANSPORT_ALLOWANCE", "Prevoz", "ADDITIONS", 10, 10, "GROSS_PLUS", "QTY_X_RATE", false, false, "AMOUNT", false, true, "TRANSPORT_BY_QUALIFYING_SHIFTS", "NONE", true},
            new Object[]{"OTHER", "Ostalo", "ADDITIONS", 20, 20, "GROSS_PLUS", "AMOUNT", true, true, "AMOUNT", true, true, "MANUAL", "AMOUNT", false},
            new Object[]{"PHONE_PREVIOUS_MONTH", "Telefon za prethodni mesec", "SETTLEMENTS", 20, 20, "DEDUCTION_MINUS", "AMOUNT", false, true, "AMOUNT", false, true, "MANUAL", "AMOUNT", false},
            new Object[]{"PAID_PREVIOUS_PERIOD", "Isplaćeno u prethodnom obračunskom periodu", "SETTLEMENTS_SUM", 20, 60, "PAYMENT_MINUS", "AMOUNT", false, false, "AMOUNT", false, true, "MANUAL", "NONE", false},
            new Object[]{"PREVIOUS_BALANCE", "Prethodno stanje", "BALANCE", 20, 70, "BALANCE_PLUS", "AMOUNT", false, false, "AMOUNT", true, true, "MANUAL", "NONE", false},
            new Object[]{"FIXED_SALARY", "Fiksni L.D.", "ADDITIONS", 30, 30, "GROSS_PLUS", "AMOUNT", true, true, "AMOUNT", false, true, "MANUAL", "AMOUNT", false},
            new Object[]{"PAID_PART_1", "Isplaćeno", "SETTLEMENTS", 30, 30, "PAYMENT_MINUS", "AMOUNT", true, true, "AMOUNT", false, true, "MANUAL", "AMOUNT", false},
            new Object[]{"MONTHLY_BONUS", "Mesečni bonus", "ADDITIONS", 40, 40, "GROSS_PLUS", "AMOUNT", false, false, "COMPONENTS", false, true, "MANUAL", "CORRECTION", true},
            new Object[]{"PAID_PART_2", "Isplaćeno drugi deo", "SETTLEMENTS", 40, 40, "DEDUCTION_MINUS", "AMOUNT", true, true, "AMOUNT", false, false, "MANUAL", "AMOUNT", false},
            new Object[]{"POSITIVE_NEGATIVE_CORRECTION", "Pozitivna / negativna korekcija", "ADDITIONS", 50, 50, "GROSS_PLUS", "AMOUNT", true, true, "AMOUNT", true, true, "MANUAL", "AMOUNT", false}
    );

    /** Everything one scenario produced, so a test can assert on any layer of it. */
    public record Scenario(
            Employee employee,
            EmployeeRecord employeeRecord,
            MonthlyReport monthlyReport,
            PayrollRun payrollRun,
            PayrollRunItem item,
            WorkCodeCategory workCategory,
            Map<String, PayrollAdjustment> adjustmentsByCode) {

        public PayrollAdjustment adjustment(String code) {
            PayrollAdjustment adjustment = adjustmentsByCode.get(code);
            if (adjustment == null) {
                throw new AssertionError("Scenario has no adjustment line " + code
                        + "; present: " + adjustmentsByCode.keySet());
            }
            return adjustment;
        }

        public boolean hasAdjustment(String code) {
            return adjustmentsByCode.containsKey(code);
        }
    }

    public Builder scenario() {
        return new Builder();
    }

    /**
     * Fluent because a payroll month has a dozen knobs and only two or three
     * matter per test; positional arguments would make every call site unreadable.
     */
    public class Builder {
        private YearMonth period = YearMonth.of(2026, 9);
        private String schemeCode = CompensationSchemeCodes.STANDARD;
        private BigDecimal hourlyRate = new BigDecimal("420.00");
        private int workMinutes = 10_560;              // 22 shifts x 8 h
        private BigDecimal weightedNormMinutes = new BigDecimal("10560.00");
        private int mealCount = 20;
        private BigDecimal performanceCoefficient = new BigDecimal("0.10");
        private double normMultiplier = 1.0;
        private BigDecimal mealRate = new BigDecimal("300.00");
        private BigDecimal transportRate = new BigDecimal("350.00");
        private BigDecimal employeeTransportRate = new BigDecimal("350.00");
        private final List<String> deniedAdjustmentCodes = new ArrayList<>();
        private boolean foreigner = false;
        private boolean commercial = false;
        private PayrollRun reuseRun = null;
        private boolean employeeHasHourlyRate = true;

        public Builder period(YearMonth value) { this.period = value; return this; }
        public Builder scheme(String value) { this.schemeCode = value; return this; }
        public Builder hourlyRate(String value) { this.hourlyRate = new BigDecimal(value); return this; }
        public Builder workMinutes(int value) { this.workMinutes = value; return this; }
        public Builder weightedNormMinutes(String value) { this.weightedNormMinutes = new BigDecimal(value); return this; }
        public Builder mealCount(int value) { this.mealCount = value; return this; }
        public Builder performanceCoefficient(String value) { this.performanceCoefficient = new BigDecimal(value); return this; }
        public Builder normMultiplier(double value) { this.normMultiplier = value; return this; }
        public Builder mealRate(String value) { this.mealRate = new BigDecimal(value); return this; }
        public Builder transportRate(String value) { this.transportRate = new BigDecimal(value); return this; }
        public Builder employeeTransportRate(String value) { this.employeeTransportRate = new BigDecimal(value); return this; }
        public Builder denyAdjustment(String... codes) { this.deniedAdjustmentCodes.addAll(List.of(codes)); return this; }
        public Builder foreigner(boolean value) { this.foreigner = value; return this; }
        public Builder commercial(boolean value) { this.commercial = value; return this; }

        /** Put this employee in an existing run, so one run holds several items. */
        public Builder inRun(PayrollRun value) { this.reuseRun = value; return this; }

        /**
         * Leave {@code employees.hourly_rate} NULL — the state 133 of 135 real
         * employees are in. The item is still initialised at {@link #hourlyRate},
         * so a test can tell "the calculation left the rate alone" apart from "the
         * calculation overwrote it with zero".
         */
        public Builder withoutEmployeeHourlyRate() { this.employeeHasHourlyRate = false; return this; }

        public Scenario build() {
            int n = COUNTER.incrementAndGet();
            LocalDate start = period.atDay(1);
            LocalDate end = period.atEndOfMonth();

            appSetting("meal_allowance_per_day", mealRate);
            appSetting("transport_allowance_per_day", transportRate);

            List<PayrollAdjustmentCategory> catalogue = catalogue();

            Employee employee = employeeRepository.saveAndFlush(Employee.builder()
                    .department(department(n))
                    .fullName("Golden Employee " + n)
                    .employeeNo("IT-GOLD-" + n)
                    .employmentStartDate(LocalDate.of(2020, 1, 1))
                    .foreigner(foreigner)
                    .worksInCommercial(commercial)
                    .active(true)
                    .normGraceDays(30)
                    .hourlyRate(employeeHasHourlyRate ? hourlyRate : null)
                    .transportAllowanceRsd(employeeTransportRate)
                    .transportAllowanceMode("AUTO")
                    .preferredLocale("sr-Latn")
                    .build());

            schemeHistoryRepository.saveAndFlush(EmployeeCompensationSchemeHistory.builder()
                    .employee(employee)
                    .compensationScheme(schemeRepository.findByCode(schemeCode).orElseThrow(
                            () -> new AssertionError("No compensation scheme " + schemeCode)))
                    .validFrom(LocalDate.of(2020, 1, 1))
                    .validUntil(null)
                    .note("Golden snapshot fixture")
                    .build());

            // D6: every active scheme x category pair needs an explicit rule before
            // anything can be calculated. Migration 2026-08-15-03 does this in
            // production; a test that invents a category or a scheme has to do the
            // same, or the scope resolver refuses to guess — which is the point.
            completeSchemeMatrix();

            for (String code : deniedAdjustmentCodes) {
                deny(schemeCode, byCode(catalogue, code));
            }

            WorkCodeCategory workCategory = workCategory(n);
            // The restricted scheme refuses every category with no rule, so the
            // one category this scenario books work against needs one. A
            // self-mapping rule is not a remap: the category stays payable.
            if (!CompensationSchemeCodes.STANDARD.equals(schemeCode)) {
                workRuleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                        .compensationScheme(schemeRepository.findByCode(schemeCode).orElseThrow())
                        .sourceCategory(workCategory)
                        .effectiveCategory(workCategory)
                        .isAllowed(true)
                        .isSelectable(true)
                        .coefficientOverride(BigDecimal.valueOf(normMultiplier))
                        .validFrom(LocalDate.of(2020, 1, 1))
                        .isActive(true)
                        .build());
            }

            EmployeeRecord record = employeeRecordRepository.saveAndFlush(EmployeeRecord.builder()
                    .employee(employee)
                    .startDate(start)
                    .endDate(end)
                    .active(true)
                    .build());

            MonthlyReport monthlyReport = monthlyReportRepository.saveAndFlush(MonthlyReport.builder()
                    .employeeRecord(record)
                    .startDate(start)
                    .endDate(end)
                    .totalShiftMinutes(workMinutes)
                    .totalWorkMinutes(workMinutes)
                    .totalAbsencePaidMinutes(0)
                    .totalAbsenceUnpaidMinutes(0)
                    .totalAbsenceMinutes(0)
                    .totalSickLeavePaidMinutes(0)
                    .totalSickLeaveUnpaidMinutes(0)
                    .totalSickLeaveMinutes(0)
                    .totalApprovedMinutes(workMinutes)
                    .totalQuantity(0)
                    .totalScrap(0)
                    .totalWeightedNormMinutes(weightedNormMinutes)
                    .performanceCoefficient(performanceCoefficient)
                    .approvedPerformanceCoefficient(performanceCoefficient)
                    .performanceRate(performanceCoefficient.multiply(BigDecimal.valueOf(100)))
                    .approvedPerformanceRate(performanceCoefficient.multiply(BigDecimal.valueOf(100)))
                    .mealAllowanceNum(mealCount)
                    .status("OPEN")
                    .calcVersion(1)
                    .version(1)
                    .build());

            monthlyReportCategoryRepository.saveAndFlush(MonthlyReportCategory.builder()
                    .monthlyReport(monthlyReport)
                    .workCodeCategory(workCategory)
                    .totalMinutes(workMinutes)
                    .totalPaidMinutes(workMinutes)
                    .totalQuantity(0)
                    .totalScrap(0)
                    .totalWeightedNormMinutes(weightedNormMinutes)
                    .totalApprovedMinutes(weightedNormMinutes)
                    .sourceType("WORK")
                    .createdAt(OffsetDateTime.now())
                    .build());

            // One run per month, as in production — uq_payroll_runs_period enforces
            // it, so several scenarios in one test share the run for their period
            // rather than each minting one.
            PayrollRun run = reuseRun != null ? reuseRun
                    : payrollRunRepository
                    .findFirstByReportYearAndReportMonth(period.getYear(), period.getMonthValue())
                    .orElseGet(() -> payrollRunRepository.saveAndFlush(PayrollRun.builder()
                            .reportYear(period.getYear())
                            .reportMonth(period.getMonthValue())
                            .runCode("IT-GOLD-RUN-" + period)
                            .status("DRAFT")
                            .createdAt(OffsetDateTime.now())
                            .build()));

            PayrollRunItem item = payrollRunItemRepository.saveAndFlush(newItem(run, employee, monthlyReport, start));

            payrollRunItemCategoryRepository.saveAndFlush(itemCategory(item, workCategory));

            // Mirrors PayrollRunInitializationTxService.createPayrollAdjustments:
            // a line the scheme excludes never gets a row, which is what actually
            // suppresses it — the update path is findByCode(...).ifPresent(...).
            Map<String, PayrollAdjustment> adjustments = new LinkedHashMap<>();
            for (PayrollAdjustmentCategory category : catalogue) {
                if (deniedAdjustmentCodes.contains(category.getCode())) {
                    continue;
                }
                adjustments.put(category.getCode(), adjustmentRepository.saveAndFlush(PayrollAdjustment.builder()
                        .payrollRunItem(item)
                        .payrollAdjustmentCategory(category)
                        .systemAmount(BigDecimal.ZERO)
                        .amount(BigDecimal.ZERO)
                        .isOverridden(false)
                        .isApplied(true)
                        .createdAt(OffsetDateTime.now())
                        .build()));
            }

            return new Scenario(employee, record, monthlyReport, run, item, workCategory, adjustments);
        }

        private WorkCodeCategory workCategory(int n) {
            return workCodeCategoryRepository.saveAndFlush(WorkCodeCategory.builder()
                    .categoryNo("IT-GOLD-WC-" + n)
                    .categoryName("Redovan rad " + n)
                    .type("WORK")
                    .isPaid(true)
                    .normMultiplier(normMultiplier)
                    .isActive(true)
                    .fixedHourlyRate(false)
                    .affectsMealAllowance(true)
                    .allowsParallelWork(false)
                    .displayOrder(10)
                    .baseCategory(true)
                    .build());
        }

        private PayrollRunItem newItem(PayrollRun run, Employee employee, MonthlyReport report, LocalDate start) {
            PayrollRunItem item = new PayrollRunItem();
            item.setPayrollRun(run);
            item.setEmployee(employee);
            item.setMonthlyReport(report);
            item.setPeriod(start);
            item.setTotalShiftMinutes(0);
            item.setTotalWorkMinutes(0);
            item.setTotalAbsenceMinutes(0);
            item.setTotalPaidAbsenceMinutes(0);
            item.setTotalUnpaidAbsenceMinutes(0);
            item.setTotalCompensatedMinutes(0);
            item.setTotalApprovedMinutes(0);
            item.setTotalQuantity(0);
            item.setTotalScrap(0);
            item.setTotalEffectiveMinutes(BigDecimal.ZERO);
            item.setPerformanceRate(BigDecimal.ZERO);
            item.setApprovedPerformanceRate(BigDecimal.ZERO);
            item.setPerformanceCoefficient(BigDecimal.ZERO);
            item.setTotalWorkDays(0);
            item.setTotalPaidDays(0);
            item.setTotalAbsenceDays(0);
            item.setManualAdjustedMinutes(0);
            item.setTotalPayrollMinutes(0);
            item.setTotalNetEarnings(BigDecimal.ZERO);
            item.setHourlyRate(hourlyRate);
            item.setHourlyRateSystem(hourlyRate);
            item.setHourlyRateOverridden(false);
            item.setBaseBonusAmountSystem(BigDecimal.ZERO);
            item.setBaseBonusAmount(BigDecimal.ZERO);
            item.setBaseBonusAmountOverridden(false);
            item.setBonusCorrectionAmountSystem(BigDecimal.ZERO);
            item.setBonusCorrectionAmount(BigDecimal.ZERO);
            item.setBonusCorrectionAmountOverridden(false);
            item.setTotalBonusAmountSystem(BigDecimal.ZERO);
            item.setTotalBonusAmount(BigDecimal.ZERO);
            item.setTotalBonusAmountOverridden(false);
            item.setTotalDeductionsAmount(BigDecimal.ZERO);
            item.setCurrentMonthTelephone(BigDecimal.ZERO);
            item.setPreviouslyPaidAmount(BigDecimal.ZERO);
            item.setPreviousNetPayableAmount(BigDecimal.ZERO);
            item.setCurrentBalanceAmount(BigDecimal.ZERO);
            item.setNetPayableAmount(BigDecimal.ZERO);
            item.setStatus("DRAFT");
            item.setCurrencyCode("RSD");
            item.setCalcVersion(1);
            item.setNeedsRecalculation(false);
            item.setCreatedAt(OffsetDateTime.now());
            return item;
        }

        private PayrollRunItemCategory itemCategory(PayrollRunItem item, WorkCodeCategory category) {
            PayrollRunItemCategory row = new PayrollRunItemCategory();
            row.setPayrollRunItem(item);
            row.setWorkCodeCategory(category);
            row.setSourceType(category.getType());
            row.setTotalMinutes(0);
            row.setTotalPaidMinutes(0);
            row.setTotalQuantity(0);
            row.setTotalScrap(0);
            row.setWeightedNormMinutes(BigDecimal.ZERO);
            row.setCategoryCoefficientSnapshot(BigDecimal.valueOf(normMultiplier));
            row.setEffectiveMinutes(BigDecimal.ZERO);
            row.setHourlyRate(hourlyRate);
            row.setAmount(BigDecimal.ZERO);
            row.setCategoryAffectsNormSnapshot(true);
            row.setCategoryAffectsBonusSnapshot(true);
            row.setCreatedAt(OffsetDateTime.now());
            return row;
        }
    }

    // ── shared building blocks ──────────────────────────────────────────────

    /** The production adjustment catalogue, created once per transaction. */
    public List<PayrollAdjustmentCategory> catalogue() {
        List<PayrollAdjustmentCategory> result = new ArrayList<>();
        for (Object[] row : CATALOGUE) {
            String code = (String) row[0];
            result.add(adjustmentCategoryRepository.findByCode(code).orElseGet(() -> {
                PayrollAdjustmentCategory category = new PayrollAdjustmentCategory();
                category.setCode(code);
                category.setName((String) row[1]);
                category.setSectionCode((String) row[2]);
                category.setSectionOrder((Integer) row[3]);
                category.setSortOrder((Integer) row[4]);
                category.setImpactCode((String) row[5]);
                category.setInputType((String) row[6]);
                category.setIsManual((Boolean) row[7]);
                category.setAllowOverride((Boolean) row[8]);
                category.setOverrideTarget((String) row[9]);
                category.setAllowNegative((Boolean) row[10]);
                category.setShowName((Boolean) row[11]);
                category.setCalculationKey((String) row[12]);
                // The edit policy from 2026-08-05-01. Without it every category
                // defaults to editable_input = NONE and nothing can be typed in
                // anywhere, which is not what production looks like.
                category.setEditableInput((String) row[13]);
                category.setAllowTotalOverride((Boolean) row[14]);
                category.setIsActive(true);
                category.setVisibleInUi(true);
                category.setVisibleInPdf(true);
                category.setCreatedAt(OffsetDateTime.now());
                return adjustmentCategoryRepository.saveAndFlush(category);
            }));
        }
        return result;
    }

    /**
     * An explicit rule for every active scheme x active category pair (D6).
     *
     * <p>What migration {@code 2026-08-15-03} does in production, and what the
     * application must do whenever a category or a scheme is created. A missing
     * rule is an incomplete configuration, not a default, so a test that skips this
     * gets an {@code IncompletePayrollConfigurationException} rather than a silent
     * guess — which is exactly the behaviour being protected.
     *
     * <p>Idempotent, and it never overwrites a rule that already exists: the denies
     * a scenario sets up must survive being asked for again.
     */
    public void completeSchemeMatrix() {
        List<CompensationScheme> schemes = schemeRepository.findAll().stream()
                .filter(CompensationScheme::isUsable)
                .toList();
        List<PayrollAdjustmentCategory> categories =
                adjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull();

        for (CompensationScheme scheme : schemes) {
            for (PayrollAdjustmentCategory category : categories) {
                if (ruleFor(scheme, category) == null) {
                    adjustmentRuleRepository.saveAndFlush(PayrollAdjustmentCategorySchemeRule.builder()
                            .compensationScheme(scheme)
                            .payrollAdjustmentCategory(category)
                            .isAllowed(true)
                            .calculationMode("INHERIT")
                            .validFrom(LocalDate.of(2020, 1, 1))
                            .isActive(true)
                            .note("Fixture: reproduces the pre-D6 ALLOW default")
                            .build());
                }
            }
        }
    }

    /**
     * Exclude one line under one scheme.
     *
     * <p>Updates the existing rule rather than inserting a second one — the
     * exclusion constraint allows only one in-force rule per scheme and category,
     * so an insert would be rejected once the matrix is complete.
     */
    public void deny(String schemeCode, PayrollAdjustmentCategory category) {
        CompensationScheme scheme = schemeRepository.findByCode(schemeCode).orElseThrow();
        PayrollAdjustmentCategorySchemeRule rule = ruleFor(scheme, category);
        if (rule == null) {
            rule = PayrollAdjustmentCategorySchemeRule.builder()
                    .compensationScheme(scheme)
                    .payrollAdjustmentCategory(category)
                    .validFrom(LocalDate.of(2020, 1, 1))
                    .isActive(true)
                    .build();
        }
        rule.setIsAllowed(false);
        rule.setCalculationMode("INHERIT");
        rule.setVisibleInUi(false);
        rule.setVisibleInPdf(false);
        rule.setNote("Fixture: excluded under " + schemeCode);
        adjustmentRuleRepository.saveAndFlush(rule);
    }

    /** Make one line exist, be visible, and always be zero under this scheme. */
    public void forceZero(String schemeCode, String categoryCode) {
        CompensationScheme scheme = schemeRepository.findByCode(schemeCode).orElseThrow();
        PayrollAdjustmentCategory category = adjustmentCategoryRepository.findByCode(categoryCode)
                .orElseThrow(() -> new AssertionError("No category " + categoryCode));
        PayrollAdjustmentCategorySchemeRule rule = ruleFor(scheme, category);
        rule.setIsAllowed(true);
        rule.setCalculationMode("ZERO");
        rule.setVisibleInUi(true);
        rule.setVisibleInPdf(true);
        rule.setShowWhenZero(true);
        rule.setEditableInput("NONE");
        rule.setAllowTotalOverride(false);
        adjustmentRuleRepository.saveAndFlush(rule);
    }

    private PayrollAdjustmentCategorySchemeRule ruleFor(CompensationScheme scheme,
                                                        PayrollAdjustmentCategory category) {
        return adjustmentRuleRepository
                .findInForceForSchemeBetween(scheme.getId(),
                        LocalDate.of(2020, 1, 1), LocalDate.of(2100, 1, 1))
                .stream()
                .filter(r -> r.getPayrollAdjustmentCategory().getId().equals(category.getId()))
                .findFirst()
                .orElse(null);
    }

    private PayrollAdjustmentCategory byCode(List<PayrollAdjustmentCategory> catalogue, String code) {
        return catalogue.stream().filter(c -> c.getCode().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("Unknown adjustment category " + code));
    }

    /**
     * Put a value on one adjustment line before the calculation runs.
     *
     * <p>The point is which TOTAL that value reaches. With every line at zero the
     * routing by {@code section_code} is invisible, so a test that wants to pin it
     * has to give the lines distinguishable amounts.
     *
     * <p>Safe to call before {@code getForPayrollAccess}: the recalculation
     * overwrites only {@code MEAL_ALLOWANCE} and {@code TRANSPORT_ALLOWANCE}, and
     * neutralises lines the scheme excludes. Everything else is left alone and is
     * then summed by {@code recalculateSummaryTotals}.
     */
    public void adjustmentAmount(Scenario scenario, String code, String amount) {
        PayrollAdjustment adjustment = scenario.adjustment(code);
        adjustment.setAmount(new BigDecimal(amount));
        adjustment.setSystemAmount(new BigDecimal(amount));
        adjustment.setIsApplied(true);
        adjustmentRepository.saveAndFlush(adjustment);
    }

    /**
     * A compensation scheme, created if the code is not seeded yet.
     *
     * <p>Lets a test describe a policy that does not exist in production yet —
     * commercial, seasonal — without a migration. That a new scheme needs no code
     * is the property the whole migration is for, so a test proving it must be
     * able to invent one.
     */
    public CompensationScheme ensureScheme(String code, String name,
                                           boolean allowUnmappedCategories,
                                           boolean allowsPerformanceBonus) {
        return schemeRepository.findByCode(code).orElseGet(() ->
                schemeRepository.saveAndFlush(CompensationScheme.builder()
                        .code(code)
                        .name(name)
                        .allowUnmappedCategories(allowUnmappedCategories)
                        .allowsPerformanceBonus(allowsPerformanceBonus)
                        .isActive(true)
                        .note("Created by PayrollScenarioFixture")
                        .build()));
    }

    /**
     * A source category that resolves onto {@code target} under {@code schemeCode}.
     *
     * <p>The source is selectable but not payable: work booked on it lands on the
     * target, so a payslip row for the source would be a permanent zero.
     */
    public WorkCodeCategory remap(String schemeCode, WorkCodeCategory target) {
        int n = COUNTER.incrementAndGet();
        WorkCodeCategory source = workCodeCategoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-GOLD-SRC-" + n)
                .categoryName("Izvorna " + n)
                .type("WORK")
                .isPaid(true)
                .normMultiplier(1.2d)
                .isActive(true)
                .fixedHourlyRate(false)
                .affectsMealAllowance(true)
                .allowsParallelWork(false)
                .displayOrder(20)
                .baseCategory(false)
                .build());

        workRuleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(schemeRepository.findByCode(schemeCode).orElseThrow())
                .sourceCategory(source)
                .effectiveCategory(target)
                .isAllowed(true)
                .isSelectable(true)
                .coefficientOverride(BigDecimal.ONE)
                .validFrom(LocalDate.of(2020, 1, 1))
                .isActive(true)
                .build());
        return source;
    }

    private Department department(int n) {
        return departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.saveAndFlush(
                        Department.builder().name("IT-GOLD-DEPT-" + n).active(true).build()));
    }

    /**
     * A date-effective app setting valid from well before any scenario period.
     *
     * <p>{@code valid_from} is a year in the past on purpose: the code under test
     * reads these with {@code OffsetDateTime.now()}, not the payroll period, so a
     * setting scoped to the scenario month would not be found at all. That defect
     * is what phase 1 fixes; the fixture must not hide it.
     */
    /**
     * The baseline rate for a key, open-ended from a year before any scenario.
     *
     * <p>Called once per scenario, so it has to be idempotent: several scenarios
     * in one test share one rate history. Asking for a different value once a rate
     * exists is a mistake rather than a second row — {@code ex_app_settings_no_overlap}
     * would reject it anyway — so it fails loudly and points at the right tool.
     */
    public void appSetting(String key, BigDecimal value) {
        AppSetting existing = openSetting(key);
        if (existing != null) {
            if (existing.getSettingValueNumeric().compareTo(value) != 0) {
                throw new AssertionError("A rate for " + key + " is already in force at "
                        + existing.getSettingValueNumeric() + "; asking for " + value
                        + " needs appSetting(key, value, validFrom, validUntil) so the"
                        + " periods form a chain.");
            }
            return;
        }
        appSetting(key, value, OffsetDateTime.now().minusYears(1), null);
    }

    /**
     * A rate that comes into force at a specific moment, closing whatever preceded it.
     *
     * <p>Building a price HISTORY is the only way to tell "priced at the payroll
     * period" apart from "priced at {@code now()}": with one open-ended rate both
     * readings return the same number and any test passes.
     *
     * <p>{@code ex_app_settings_no_overlap} excludes overlapping
     * {@code tstzrange(valid_from, COALESCE(valid_until, 'infinity'))} per key, so
     * a rate history is a chain. The open period is closed AT {@code validFrom} —
     * the range is half-open, so the two touch without overlapping.
     */
    public void appSetting(String key, BigDecimal value,
                           OffsetDateTime validFrom, OffsetDateTime validUntil) {
        AppSetting predecessor = openSetting(key);
        if (predecessor != null && predecessor.getValidFrom().isBefore(validFrom)) {
            predecessor.setValidUntil(validFrom);
            appSettingRepository.saveAndFlush(predecessor);
        }
        appSettingRepository.saveAndFlush(AppSetting.builder()
                .settingKey(key)
                .valueType("number")
                .settingValueNumeric(value)
                .affectsPayroll(true)
                .isActive(true)
                .validFrom(validFrom)
                .validUntil(validUntil)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    // ── bonus configuration ─────────────────────────────────────────────────

    /** Give the employee a bonus category worth {@code amount}. */
    public void bonusCategory(Employee employee, String amount) {
        int n = COUNTER.incrementAndGet();
        BonusCategory category = bonusCategoryRepository.saveAndFlush(BonusCategory.builder()
                .categoryNo("IT-BON-" + n)
                .categoryName("Bonus kategorija " + n)
                .bonusAmount(new BigDecimal(amount))
                .minHours(new BigDecimal("0.00"))
                .active(true)
                .validFrom(LocalDate.of(2020, 1, 1))
                .build());

        employeeBonusRepository.saveAndFlush(EmployeeBonus.builder()
                .employee(employee)
                .bonusCategory(category)
                .startDate(LocalDate.of(2020, 1, 1))
                .createdAt(OffsetDateTime.now())
                .build());
    }

    /** The hours an employee must work that month before the base bonus is earned. */
    public void bonusMinHours(YearMonth period, int minHours) {
        bonusMinHoursRuleRepository.saveAndFlush(BonusMinHoursRule.builder()
                .period(period.atDay(1))
                .minNumHours(minHours)
                .build());
    }

    /** One hours threshold and what reaching it adds on top of the base. */
    public void bonusTier(YearMonth period, int minHours, String bonusValue) {
        bonusEligibilityRuleRepository.saveAndFlush(BonusEligibilityRule.builder()
                .period(period.atDay(1))
                .minNumHours(minHours)
                .bonusValue(new BigDecimal(bonusValue))
                .isActive(true)
                .build());
    }

    /** Make one line refuse to let the payroll item be locked until it is filled in. */
    public void requireManualInput(String categoryCode) {
        PayrollAdjustmentCategory category = adjustmentCategoryRepository.findByCode(categoryCode)
                .orElseThrow(() -> new AssertionError("No category " + categoryCode));
        category.setRequiredManualInput(true);
        adjustmentCategoryRepository.saveAndFlush(category);
    }

    /** The currently open-ended row for a key, or null. */
    private AppSetting openSetting(String key) {
        return appSettingRepository.findAll().stream()
                .filter(s -> key.equalsIgnoreCase(s.getSettingKey()))
                .filter(s -> s.getArchivedAt() == null && s.getValidUntil() == null)
                .findFirst()
                .orElse(null);
    }

    /**
     * One shift record plus its {@code daily_reports} row.
     *
     * <p>The transport rule counts these: one unit per distinct work-shift record
     * with {@code total_work_minutes > 0}. {@code uq_daily_reports_employee_shift}
     * guarantees one row per shift, which is what makes a plain count correct.
     */
    public DailyReport dailyReport(Employee employee, LocalDate workDate,
                                   int shiftMinutes, int workMinutes) {
        return dailyReport(employee, workDate, 6, shiftMinutes, workMinutes);
    }

    /**
     * @param startHour when the shift begins. Explicit because
     *                  {@code ex_work_shifts_no_overlap} refuses two shifts of the
     *                  same employee whose wall-clock ranges overlap — two shifts
     *                  on one day have to be genuinely separate, and a shift that
     *                  crosses midnight has to start late enough to do so.
     */
    public DailyReport dailyReport(Employee employee, LocalDate workDate,
                                   int startHour, int shiftMinutes, int workMinutes) {
        WorkShift shift = workShift(employee, workDate, startHour, shiftMinutes);
        return dailyReportRepository.saveAndFlush(DailyReport.builder()
                .employee(employee)
                .workDate(workDate)
                .workShift(shift)
                .totalShiftMinutes(shiftMinutes)
                .totalWorkMinutes(workMinutes)
                .totalAbsencePaidMinutes(0)
                .totalAbsenceUnpaidMinutes(0)
                .totalSickLeavePaidMinutes(0)
                .totalSickLeaveUnpaidMinutes(0)
                .totalCompensatedMinutes(0)
                .totalApprovedMinutes(workMinutes)
                .bonusEligibleMinutes(workMinutes)
                .totalQuantity(0)
                .totalScrap(0)
                .totalWeightedNormMinutes(BigDecimal.valueOf(workMinutes))
                .performanceRate(BigDecimal.ZERO)
                .approvedPerformanceRate(BigDecimal.ZERO)
                .performanceCoefficient(BigDecimal.ZERO)
                .approvedPerformanceCoefficient(BigDecimal.ZERO)
                .calcVersion(1)
                .version(0)
                .isMealAllowed(workMinutes > 0)
                .mealsCount(workMinutes > 0 ? 1 : 0)
                .build());
    }

    /**
     * A shift record. It may run past midnight — {@code work_date} still names the
     * day it started on, which is why such a shift is one record and one transport
     * unit rather than two.
     */
    public WorkShift workShift(Employee employee, LocalDate workDate, int startHour, int minutes) {
        OffsetDateTime startAt = workDate.atTime(LocalTime.of(startHour, 0)).atOffset(ZoneOffset.UTC);
        return workShiftRepository.saveAndFlush(WorkShift.builder()
                .employee(employee)
                .shift(shift(startHour, minutes))
                .startAt(startAt)
                .endAt(startAt.plusMinutes(minutes))
                .workDate(workDate)
                .isActive(true)
                .lastActivityAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .version(0L)
                .build());
    }

    /**
     * The shift type, keyed by start hour.
     *
     * <p>Two shift records for one employee on one day need two different types:
     * {@code uq_work_shifts_employee_shift_work_date} is unique on
     * {@code (employee_id, shift_id, work_date)}. That constraint is also why "two
     * arrivals on one day" can only mean two different shift types — the schema
     * cannot represent the same type twice.
     */
    private Shift shift(int startHour, int minutes) {
        String code = "IT-GOLD-S-" + startHour;
        return shiftRepository.findAll().stream()
                .filter(s -> code.equals(s.getShiftCode()))
                .findFirst()
                .orElseGet(() -> shiftRepository.saveAndFlush(Shift.builder()
                        .shiftCode(code)
                        .name("Smena od " + startHour + "h")
                        .startTime(LocalTime.of(startHour, 0))
                        .endTime(LocalTime.of(startHour, 0).plusMinutes(minutes))
                        .isActive(true)
                        .createdAt(OffsetDateTime.now())
                        .build()));
    }
}
