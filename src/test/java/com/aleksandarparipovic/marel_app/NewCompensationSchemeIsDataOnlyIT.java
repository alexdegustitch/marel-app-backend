package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRule;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRuleRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScope;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScopeService;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolution;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolutionService;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A brand-new worker type, added as DATA ONLY.
 *
 * <p>The promise being protected: introducing a compensation scheme — a seasonal
 * worker, a trainee, anything — is rows in three tables and no Java change. This
 * test creates a scheme this codebase has never heard of and drives the whole
 * resolution and payroll-scoping path through it.
 *
 * <p>It is a guard against the obvious regression: somebody adding
 * {@code if (scheme.getCode().equals("..."))} somewhere. Exactly one place in
 * {@code src/main} names a scheme at all — {@code CompensationSchemeInitializer},
 * deciding which scheme a newly created employee opens on — and nothing in the
 * calculation path does.
 */
@Transactional
class NewCompensationSchemeIsDataOnlyIT extends AbstractIntegrationTest {

    @Autowired private WorkCategoryResolutionService resolutionService;
    @Autowired private PayrollSchemeScopeService scopeService;
    @Autowired private com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture fixture;
    @Autowired private CompensationSchemeRepository schemeRepository;
    @Autowired private EmployeeCompensationSchemeHistoryRepository historyRepository;
    @Autowired private WorkCodeCategorySchemeRuleRepository workRuleRepository;
    @Autowired private PayrollAdjustmentCategoryRepository adjustmentCategoryRepository;
    @Autowired private PayrollAdjustmentCategorySchemeRuleRepository adjustmentRuleRepository;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final LocalDate RULES_FROM = LocalDate.of(2020, 1, 1);
    private static final LocalDate WORKDAY = LocalDate.of(2026, 9, 15);
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 9, 30);

    private WorkCodeCategory category(String suffix, double multiplier) {
        int n = COUNTER.incrementAndGet();
        return categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-SEZ-" + suffix + "-" + n).categoryName("Kategorija " + suffix + " " + n)
                .type("WORK").isPaid(true).normMultiplier(multiplier).isActive(true)
                .fixedHourlyRate(false).affectsMealAllowance(true).allowsParallelWork(false)
                .displayOrder(0).baseCategory(false).validFrom(RULES_FROM).build());
    }

    private PayrollAdjustmentCategory adjustmentCategory(String suffix) {
        int n = COUNTER.incrementAndGet();
        PayrollAdjustmentCategory c = new PayrollAdjustmentCategory();
        c.setCode("IT-SEZ-ADJ-" + suffix + "-" + n);
        c.setName("Stavka " + suffix + " " + n);
        c.setSectionCode("ADDITIONS");
        c.setSectionOrder(0);
        c.setSortOrder(0);
        c.setImpactCode("GROSS_PLUS");
        c.setIsManual(true);
        c.setAllowOverride(false);
        c.setOverrideTarget("AMOUNT");
        c.setAllowNegative(false);
        c.setIsActive(true);
        c.setVisibleInUi(true);
        c.setVisibleInPdf(true);
        c.setShowName(true);
        c.setCreatedAt(OffsetDateTime.now());
        return adjustmentCategoryRepository.saveAndFlush(c);
    }

    private Employee anEmployee() {
        int n = COUNTER.incrementAndGet();
        Department department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.saveAndFlush(
                        Department.builder().name("IT-DEPT-" + n).active(true).build()));
        return employeeRepository.saveAndFlush(Employee.builder()
                .department(department).fullName("Sezonac " + n).employeeNo("IT-SEZ-EMP-" + n)
                .employmentStartDate(LocalDate.of(2020, 1, 1)).foreigner(false).active(true)
                .normGraceDays(30).transportAllowanceMode("AUTO").worksInCommercial(false)
                .preferredLocale("sr-Latn").build());
    }

    @Test
    @DisplayName("a seasonal-worker scheme this codebase has never heard of works end to end, as data only")
    void aBrandNewSchemeNeedsNoCode() {
        // ── Step 1: the scheme. Closed, and paying no bonus. ────────────────
        CompensationScheme seasonal = schemeRepository.saveAndFlush(CompensationScheme.builder()
                .code("IT-SEASONAL-" + COUNTER.incrementAndGet())
                .name("Sezonski radnik")
                .allowUnmappedCategories(false)
                .allowsPerformanceBonus(false)
                .isActive(true)
                .build());

        // ── Step 2: work-category rules. ───────────────────────────────────
        WorkCodeCategory worked = category("RAD", 1.3d);       // what a supervisor picks
        WorkCodeCategory target = category("CILJ", 1.0d);      // what it is calculated as
        WorkCodeCategory leave = category("ODS", 1.0d);        // passes through untouched
        WorkCodeCategory forbidden = category("ZABRANJENO", 1.1d);

        workRuleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(seasonal).sourceCategory(worked).effectiveCategory(target)
                .isAllowed(true).isSelectable(true)
                .coefficientOverride(new BigDecimal("0.90"))
                .validFrom(RULES_FROM).isActive(true).build());

        // The target: reachable by the calculation, never offered for selection.
        workRuleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(seasonal).sourceCategory(target).effectiveCategory(target)
                .isAllowed(true).isSelectable(false)
                .coefficientOverride(new BigDecimal("0.90"))
                .validFrom(RULES_FROM).isActive(true).build());

        workRuleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(seasonal).sourceCategory(leave).effectiveCategory(null)
                .isAllowed(true).isSelectable(true).coefficientOverride(null)
                .validFrom(RULES_FROM).isActive(true).build());

        // ── Step 3: an excluded payroll line. ──────────────────────────────
        PayrollAdjustmentCategory excluded = adjustmentCategory("NEMA");
        PayrollAdjustmentCategory kept = adjustmentCategory("IMA");
        adjustmentRuleRepository.saveAndFlush(PayrollAdjustmentCategorySchemeRule.builder()
                .compensationScheme(seasonal).payrollAdjustmentCategory(excluded)
                .isAllowed(false).validFrom(RULES_FROM).isActive(true).build());

        // ── Step 3b: the rest of the matrix (D6). ──────────────────────────
        // A new scheme is data, but it is not COMPLETE data until every active
        // category has a rule under it. That is the one thing a new scheme cannot
        // skip: the calculation refuses to guess what a missing rule means, so
        // "needs no code" does not extend to "needs no configuration".
        fixture.completeSchemeMatrix();

        // ── Step 4: put an employee on it. ─────────────────────────────────
        Employee employee = anEmployee();
        historyRepository.saveAndFlush(EmployeeCompensationSchemeHistory.builder()
                .employee(employee).compensationScheme(seasonal)
                .validFrom(LocalDate.of(2020, 1, 1)).build());

        // ── That is the entire setup. Nothing below knows this scheme exists. ──

        WorkCategoryResolution workedResolution =
                resolutionService.resolve(employee.getId(), WORKDAY, worked.getId());
        assertThat(workedResolution.allowed()).isTrue();
        assertThat(workedResolution.effectiveCategoryId()).isEqualTo(target.getId());
        assertThat(workedResolution.coefficient())
                .as("the scheme's own override, not the category's 1.3")
                .isEqualByComparingTo("0.90");

        WorkCategoryResolution leaveResolution =
                resolutionService.resolve(employee.getId(), WORKDAY, leave.getId());
        assertThat(leaveResolution.effectiveCategoryId())
                .as("no remap: the effective category IS the source")
                .isEqualTo(leave.getId());
        assertThat(leaveResolution.coefficient())
                .as("no override: the category's own multiplier")
                .isEqualByComparingTo("1.0");

        assertThat(resolutionService.resolve(employee.getId(), WORKDAY, forbidden.getId()).allowed())
                .as("no rule and the scheme is closed")
                .isFalse();

        List<Long> selectable = resolutionService.listAllowedCategories(employee.getId(), WORKDAY)
                .stream().map(WorkCategoryResolution::sourceCategoryId).toList();
        assertThat(selectable).contains(worked.getId(), leave.getId());
        assertThat(selectable)
                .as("the calculation target is reachable but never offered")
                .doesNotContain(target.getId())
                .doesNotContain(forbidden.getId());

        // ── Payroll scoping, same story ────────────────────────────────────
        PayrollSchemeScope scope = scopeService.scopeFor(employee.getId(), PERIOD_START, PERIOD_END);

        assertThat(scope).isNotNull();
        assertThat(scope.allowsWorkCategory(worked.getId()))
                .as("remapped, so nothing can accumulate against it — no payroll row")
                .isFalse();
        assertThat(scope.allowsWorkCategory(target.getId()))
                .as("payable: it is where the money lands").isTrue();
        assertThat(scope.allowsWorkCategory(leave.getId()))
                .as("no remap, so the work stays here and it must appear").isTrue();
        assertThat(scope.allowsWorkCategory(forbidden.getId())).isFalse();
        assertThat(scope.allowsAdjustmentCategory(kept.getId())).isTrue();
        assertThat(scope.allowsAdjustmentCategory(excluded.getId())).isFalse();
        assertThat(scope.allowsPerformanceBonus())
                .as("no bonus for this worker type").isFalse();
    }

    @Test
    @DisplayName("nothing in the calculation path branches on which scheme it is")
    void noCodeNamesAScheme() {
        // Two schemes differing ONLY in their configuration produce different
        // answers for the same category — which is only possible if the
        // behaviour comes from the data and not from a name somewhere.
        WorkCodeCategory shared = category("DELJENA", 1.4d);

        CompensationScheme open = schemeRepository.saveAndFlush(CompensationScheme.builder()
                .code("IT-OPEN-" + COUNTER.incrementAndGet()).name("Otvorena")
                .allowUnmappedCategories(true).allowsPerformanceBonus(true).isActive(true).build());
        CompensationScheme closed = schemeRepository.saveAndFlush(CompensationScheme.builder()
                .code("IT-CLOSED-" + COUNTER.incrementAndGet()).name("Zatvorena")
                .allowUnmappedCategories(false).allowsPerformanceBonus(false).isActive(true).build());

        Employee onOpen = anEmployee();
        historyRepository.saveAndFlush(EmployeeCompensationSchemeHistory.builder()
                .employee(onOpen).compensationScheme(open).validFrom(LocalDate.of(2020, 1, 1)).build());
        Employee onClosed = anEmployee();
        historyRepository.saveAndFlush(EmployeeCompensationSchemeHistory.builder()
                .employee(onClosed).compensationScheme(closed).validFrom(LocalDate.of(2020, 1, 1)).build());

        assertThat(resolutionService.resolve(onOpen.getId(), WORKDAY, shared.getId()).allowed()).isTrue();
        assertThat(resolutionService.resolve(onClosed.getId(), WORKDAY, shared.getId()).allowed()).isFalse();

        assertThat(scopeService.scopeFor(onOpen.getId(), PERIOD_START, PERIOD_END)
                .allowsPerformanceBonus()).isTrue();
        assertThat(scopeService.scopeFor(onClosed.getId(), PERIOD_START, PERIOD_END)
                .allowsPerformanceBonus()).isFalse();
    }
}
