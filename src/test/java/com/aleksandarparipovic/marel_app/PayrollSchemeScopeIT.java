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
import com.aleksandarparipovic.marel_app.work_category_resolution.IncompletePayrollConfigurationException;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScope;
import com.aleksandarparipovic.marel_app.work_category_resolution.PayrollSchemeScopeService;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired private WorkCodeCategorySchemeRuleRepository workRuleRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture fixture;

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
        PayrollAdjustmentCategory saved = adjustmentCategoryRepository.saveAndFlush(c);
        // D6: a new category is not usable until every active scheme has a rule for
        // it. That is the lifecycle the application must enforce, so a test that
        // invents a category has to satisfy it too.
        fixture.completeSchemeMatrix();
        return saved;
    }

    private void deny(String schemeCode, PayrollAdjustmentCategory category) {
        fixture.deny(schemeCode, category);
    }

    /** A source category that remaps onto {@code target} under the restricted scheme. */
    private WorkCodeCategory remappingTo(WorkCodeCategory target) {
        int n = COUNTER.incrementAndGet();
        WorkCodeCategory source = categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-REMAP-" + n).categoryName("Izvorna " + n).type("WORK")
                .isPaid(true).normMultiplier(1.2d).isActive(true).fixedHourlyRate(false)
                .affectsMealAllowance(true).allowsParallelWork(false).displayOrder(0)
                .baseCategory(false).build());

        workRuleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(schemeRepository
                        .findByCode(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).orElseThrow())
                .sourceCategory(source)
                .effectiveCategory(target)
                .isAllowed(true)
                .coefficientOverride(java.math.BigDecimal.ONE)
                .validFrom(LocalDate.of(2026, 8, 1))
                .isActive(true)
                .build());
        return source;
    }

    private PayrollSchemeScope scopeOf(Employee employee) {
        return scopeService.scopeFor(employee.getId(), PERIOD_START, PERIOD_END);
    }

    // ── the two opposite defaults ───────────────────────────────────────────

    @Test
    @DisplayName("an adjustment category with NO rule is a configuration error, not a default")
    void adjustmentCategoryWithoutARuleIsAnError() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        int n = COUNTER.incrementAndGet();
        PayrollAdjustmentCategory orphan = new PayrollAdjustmentCategory();
        orphan.setCode("IT-ORPHAN-" + n);
        orphan.setName("Bez pravila " + n);
        orphan.setSectionCode("ADDITIONS");
        orphan.setSectionOrder(0);
        orphan.setSortOrder(0);
        orphan.setImpactCode("GROSS_PLUS");
        orphan.setIsManual(true);
        orphan.setAllowOverride(false);
        orphan.setOverrideTarget("AMOUNT");
        orphan.setAllowNegative(false);
        orphan.setIsActive(true);
        orphan.setVisibleInUi(true);
        orphan.setVisibleInPdf(true);
        orphan.setShowName(true);
        orphan.setCreatedAt(OffsetDateTime.now());
        adjustmentCategoryRepository.saveAndFlush(orphan);
        // Deliberately NOT completing the matrix.

        // THIS REVERSES THE OLD DEFAULT (D6). A missing rule used to mean ALLOW,
        // chosen because a line that silently disappears is harder to notice than
        // an extra one. Both defaults hide the same thing though: that nobody ever
        // decided. Now the calculation refuses to guess and says which line it
        // cannot answer for.
        assertThatThrownBy(() -> scopeOf(employee))
                .isInstanceOf(IncompletePayrollConfigurationException.class)
                .hasMessageContaining(orphan.getCode());
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

        // What makes the target payable is a rule REMAPPING onto it, not a rule
        // OF it — its own rule denies it, because nobody selects it. The test
        // schema carries no business data, so the remap the real seed provides
        // (J -> S, D -> S, ...) has to be built here.
        WorkCodeCategory source = remappingTo(common);

        PayrollSchemeScope scope = scopeOf(restricted);

        // THREE questions, three answers, and the payslip only cares about the
        // third:
        //   may a supervisor SELECT it?   -> the source, yes (covered elsewhere)
        //   may the calculation RESOLVE it? -> both, yes
        //   may money LAND on it?         -> only the target
        //
        // The source remaps, so nothing can ever accumulate against it and a row
        // for it would be a permanent zero.
        assertThat(scope.allowsWorkCategory(source.getId()))
                .as("a remapped source never carries money, so it gets no payroll row")
                .isFalse();
        assertThat(scope.allowsWorkCategory(common.getId()))
                .as("the target does, which is where the money ends up")
                .isTrue();
    }

    // ── bonuses ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a self-mapping rule stays payable — that is how the target earns its row")
    void selfMappingIsPayable() {
        Employee restricted = anEmployee();
        period(restricted, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        WorkCodeCategory common = categoryRepository.findAll().stream()
                .filter(c -> "S".equalsIgnoreCase(c.getCategoryNo())
                        || "FOREIGN_ALL_SHIFTS".equalsIgnoreCase(c.getCategoryNo()))
                .findFirst().orElseThrow();

        // S -> S is not a remap: it does not send the money anywhere else.
        assertThat(scopeOf(restricted).allowsWorkCategory(common.getId())).isTrue();
    }

    @Test
    @DisplayName("a pass-through rule keeps its own category payable")
    void passThroughIsPayable() {
        Employee restricted = anEmployee();
        period(restricted, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        int n = COUNTER.incrementAndGet();
        WorkCodeCategory leave = categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo("IT-PASS-" + n).categoryName("Odsustvo " + n).type("ABSENCE")
                .isPaid(true).normMultiplier(1.0d).isActive(true).fixedHourlyRate(false)
                .affectsMealAllowance(false).allowsParallelWork(false).displayOrder(0)
                .baseCategory(false).build());

        workRuleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(schemeRepository
                        .findByCode(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT).orElseThrow())
                .sourceCategory(leave).effectiveCategory(null)
                .isAllowed(true).coefficientOverride(null)
                .validFrom(LocalDate.of(2020, 1, 1)).isActive(true).build());

        // No remap, so the work stays on this category and it must appear.
        assertThat(scopeOf(restricted).allowsWorkCategory(leave.getId())).isTrue();
    }

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
    @DisplayName("two schemes in one payroll month is an error, not a union")
    void twoSchemesInOneMonthIsAnError() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD,
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 14));
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT,
                LocalDate.of(2026, 9, 15), null);

        // D1: a scheme change now takes effect on the first day of the FOLLOWING
        // month, so this pair cannot be created through the application at all. One
        // that predates the rule needs a person to look at it, not an average.
        //
        // The union this replaces was not merely redundant, it leaked: a restricted
        // employee inherited every permission of the scheme they had left, for the
        // whole month, in both directions.
        assertThatThrownBy(() -> scopeOf(employee))
                .isInstanceOf(IncompletePayrollConfigurationException.class)
                .hasMessageContaining("tačno jedan");
    }

    @Test
    @DisplayName("an employee with no scheme period at all is an error, not 'unrestricted'")
    void noSchemeIsAnError() {
        Employee employee = anEmployee();

        // It used to return null, and every caller read null as "no restriction
        // known" and fell back to the full list — the quietest possible way to pay
        // somebody under a policy nobody chose. Production has no such employees
        // (diagnostic Q4), so making it loud costs nothing and closes the hole.
        assertThatThrownBy(() -> scopeService.scopeFor(employee.getId(), PERIOD_START, PERIOD_END))
                .isInstanceOf(IncompletePayrollConfigurationException.class)
                .hasMessageContaining("nema način obračuna");
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
