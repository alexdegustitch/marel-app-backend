package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
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
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a compensation scheme allows across a payroll PERIOD, as opposed to on a
 * single work date.
 *
 * <p>The rules being protected are the two defaults — work categories closed,
 * adjustment lines open — and the union-across-the-month behaviour that stops a
 * mid-month scheme change from making recorded work disappear from the payslip.
 */
@Transactional
class PayrollSchemeScopeIT extends AbstractIntegrationTest {

    @Autowired private PayrollSchemeScopeService scopeService;
    @Autowired private CompensationSchemeRepository schemeRepository;
    @Autowired private EmployeeCompensationSchemeHistoryRepository historyRepository;
    @Autowired private PayrollAdjustmentCategoryRepository adjustmentCategoryRepository;
    @Autowired private PayrollAdjustmentCategorySchemeRuleRepository adjustmentRuleRepository;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 9, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 9, 30);

    private Employee anEmployee() {
        int n = COUNTER.incrementAndGet();
        Department department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.saveAndFlush(
                        Department.builder().name("IT-DEPT-" + n).active(true).build()));
        return employeeRepository.saveAndFlush(Employee.builder()
                .department(department).fullName("Scope Employee " + n).employeeNo("IT-SCOPE-" + n)
                .employmentStartDate(LocalDate.of(2020, 1, 1)).foreigner(false).active(true)
                .normGraceDays(30).transportAllowanceMode("AUTO").worksInCommercial(false)
                .preferredLocale("sr-Latn").build());
    }

    private void period(Employee employee, String schemeCode, LocalDate from, LocalDate until) {
        historyRepository.saveAndFlush(EmployeeCompensationSchemeHistory.builder()
                .employee(employee)
                .compensationScheme(schemeRepository.findByCode(schemeCode).orElseThrow())
                .validFrom(from).validUntil(until).build());
    }

    private PayrollAdjustmentCategory adjustmentCategory() {
        int n = COUNTER.incrementAndGet();
        PayrollAdjustmentCategory c = new PayrollAdjustmentCategory();
        c.setCode("IT-SCOPE-ADJ-" + n);
        c.setName("Stavka " + n);
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

    private void deny(String schemeCode, PayrollAdjustmentCategory category) {
        adjustmentRuleRepository.saveAndFlush(PayrollAdjustmentCategorySchemeRule.builder()
                .compensationScheme(schemeRepository.findByCode(schemeCode).orElseThrow())
                .payrollAdjustmentCategory(category)
                .isAllowed(false)
                .validFrom(LocalDate.of(2026, 8, 1))
                .isActive(true)
                .build());
    }

    private PayrollSchemeScope scopeOf(Employee employee) {
        return scopeService.scopeFor(employee.getId(), PERIOD_START, PERIOD_END);
    }

    // ── the two opposite defaults ───────────────────────────────────────────

    @Test
    @DisplayName("an adjustment category with no rule is ALLOWED, even under the restricted scheme")
    void adjustmentCategoriesAreOpenByDefault() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        PayrollAdjustmentCategory fresh = adjustmentCategory();

        // The opposite default from work categories, on purpose: a payslip line
        // that silently disappears is much harder to notice than an extra one.
        assertThat(scopeOf(employee).allowsAdjustmentCategory(fresh.getId())).isTrue();
    }

    @Test
    @DisplayName("a deny rule removes the adjustment line")
    void denyRuleRemovesTheLine() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        PayrollAdjustmentCategory denied = adjustmentCategory();
        deny(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, denied);

        assertThat(scopeOf(employee).allowsAdjustmentCategory(denied.getId())).isFalse();
    }

    @Test
    @DisplayName("a deny rule for one scheme does not affect another")
    void denyIsPerScheme() {
        Employee standard = anEmployee();
        period(standard, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);
        PayrollAdjustmentCategory denied = adjustmentCategory();
        deny(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, denied);

        assertThat(scopeOf(standard).allowsAdjustmentCategory(denied.getId())).isTrue();
    }

    @Test
    @DisplayName("a work category with no rule is REFUSED under the restricted scheme but allowed under the standard one")
    void workCategoriesAreClosedByDefaultOnlyForTheRestrictedScheme() {
        int n = COUNTER.incrementAndGet();
        WorkCodeCategory unmapped = categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-SCOPE-WC-" + n).categoryName("Kategorija " + n).type("WORK")
                .isPaid(true).normMultiplier(1.1d).isActive(true).fixedHourlyRate(false)
                .affectsMealAllowance(true).allowsParallelWork(false).displayOrder(0)
                .baseCategory(false).build());

        Employee standard = anEmployee();
        period(standard, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);
        Employee restricted = anEmployee();
        period(restricted, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        assertThat(scopeOf(standard).allowsWorkCategory(unmapped.getId())).isTrue();
        assertThat(scopeOf(restricted).allowsWorkCategory(unmapped.getId())).isFalse();
    }

    @Test
    @DisplayName("the remap target can appear on the payslip even though nobody selects it")
    void remapTargetIsPayable() {
        Employee restricted = anEmployee();
        period(restricted, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        WorkCodeCategory common = categoryRepository.findAll().stream()
                .filter(c -> "S".equalsIgnoreCase(c.getCategoryNo())
                        || "FOREIGN_ALL_SHIFTS".equalsIgnoreCase(c.getCategoryNo()))
                .findFirst().orElseThrow();

        // This is where the money lands, so it must have a payroll row.
        assertThat(scopeOf(restricted).allowsWorkCategory(common.getId())).isTrue();
    }

    // ── bonuses ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the restricted scheme pays no performance bonus; the standard one does")
    void performanceBonusFollowsTheScheme() {
        Employee standard = anEmployee();
        period(standard, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);
        Employee restricted = anEmployee();
        period(restricted, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        assertThat(scopeOf(standard).allowsPerformanceBonus()).isTrue();
        assertThat(scopeOf(restricted).allowsPerformanceBonus())
                .as("no bonus on top — efficiency still weights the minutes themselves")
                .isFalse();

        CompensationScheme fixed = schemeRepository
                .findByCode(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).orElseThrow();
        assertThat(fixed.getAllowsPerformanceBonus()).isFalse();
    }

    // ── mid-month scheme change ─────────────────────────────────────────────

    @Test
    @DisplayName("a mid-month scheme change unions both schemes, so recorded work keeps a payroll row")
    void midMonthChangeUnionsBothSchemes() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD,
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 14));
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT,
                LocalDate.of(2026, 9, 15), null);

        int n = COUNTER.incrementAndGet();
        WorkCodeCategory standardOnly = categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-MID-" + n).categoryName("Samo standard " + n).type("WORK")
                .isPaid(true).normMultiplier(1.1d).isActive(true).fixedHourlyRate(false)
                .affectsMealAllowance(true).allowsParallelWork(false).displayOrder(0)
                .baseCategory(false).build());

        PayrollSchemeScope scope = scopeOf(employee);

        // Allowed under STANDARD for the first half of the month. Excluding it
        // would leave minutes already recorded against it with nowhere to land.
        assertThat(scope.allowsWorkCategory(standardOnly.getId()))
                .as("union, not intersection: being too generous shows a zero row, being too strict loses money")
                .isTrue();

        // The bonus follows the same union rule.
        assertThat(scope.allowsPerformanceBonus()).isTrue();
    }

    @Test
    @DisplayName("an employee with no scheme period at all yields no scope, which callers read as unrestricted")
    void noSchemeYieldsNoScope() {
        Employee employee = anEmployee();

        assertThat(scopeService.scopeFor(employee.getId(), PERIOD_START, PERIOD_END)).isNull();
    }

    @Test
    @DisplayName("the batched lookup answers many employees at once")
    void batchedLookup() {
        Employee a = anEmployee();
        Employee b = anEmployee();
        period(a, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);
        period(b, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        var scopes = scopeService.scopesFor(
                List.of(a.getId(), b.getId()), PERIOD_START, PERIOD_END,
                categoryRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByDisplayOrderAscIdAsc(),
                adjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull());

        assertThat(scopes).containsOnlyKeys(a.getId(), b.getId());
        assertThat(scopes.get(a.getId()).allowsPerformanceBonus()).isTrue();
        assertThat(scopes.get(b.getId()).allowsPerformanceBonus()).isFalse();
    }
}
