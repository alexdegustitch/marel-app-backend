package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeCodes;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.DepartmentRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolution;
import com.aleksandarparipovic.marel_app.work_category_resolution.WorkCategoryResolutionService;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compensation-scheme resolution rules, against the real schema.
 *
 * <p>These need a database rather than mocks because most of what is being
 * asserted IS the database: the GiST exclusion constraints against overlapping
 * periods and overlapping rules, the seeded schemes and rules, the NUMERIC scale
 * of a coefficient, and the date-window filtering in the repository queries.
 */
@Transactional
class CompensationSchemeResolutionIT extends AbstractIntegrationTest {

    @Autowired private WorkCategoryResolutionService resolutionService;
    @Autowired private CompensationSchemeRepository schemeRepository;
    @Autowired private EmployeeCompensationSchemeHistoryRepository historyRepository;
    @Autowired private WorkCodeCategorySchemeRuleRepository ruleRepository;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EntityManager entityManager;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** The cutover the backfill migration uses; scheme rules start here too. */
    private static final LocalDate RULES_FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate WORKDAY = LocalDate.of(2026, 9, 15);

    // ── fixtures ────────────────────────────────────────────────────────────

    private Employee anEmployee() {
        int n = COUNTER.incrementAndGet();
        Department department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.saveAndFlush(
                        Department.builder().name("IT-DEPT-" + n).active(true).build()));

        return employeeRepository.saveAndFlush(Employee.builder()
                .department(department)
                .fullName("Test Employee " + n)
                .employeeNo("IT-EMP-" + n)
                .employmentStartDate(LocalDate.of(2020, 1, 1))
                .foreigner(false)
                .active(true)
                .normGraceDays(30)
                .transportAllowanceMode("AUTO")
                .worksInCommercial(false)
                .preferredLocale("sr-Latn")
                .build());
    }

    private WorkCodeCategory category(String no, double multiplier) {
        return categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo(no)
                .categoryName("Category " + no)
                .type("WORK")
                .isPaid(true)
                .normMultiplier(multiplier)
                .isActive(true)
                .fixedHourlyRate(false)
                .affectsMealAllowance(true)
                .allowsParallelWork(false)
                .displayOrder(0)
                .baseCategory(false)
                .build());
    }

    private CompensationScheme scheme(String code) {
        return schemeRepository.findByCode(code).orElseThrow();
    }

    private EmployeeCompensationSchemeHistory period(Employee employee, String schemeCode,
                                                     LocalDate from, LocalDate until) {
        return historyRepository.saveAndFlush(EmployeeCompensationSchemeHistory.builder()
                .employee(employee)
                .compensationScheme(scheme(schemeCode))
                .validFrom(from)
                .validUntil(until)
                .build());
    }

    /** Created unconditionally by 2026-07-27-03, so it exists even in an empty database. */
    private WorkCodeCategory foreignAllShifts() {
        return categoryRepository.findAll().stream()
                .filter(c -> "FOREIGN_ALL_SHIFTS".equalsIgnoreCase(c.getCategoryNo()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "FOREIGN_ALL_SHIFTS missing — 2026-07-27-03 should create it"));
    }

    /**
     * The shape the seeded rules have, rebuilt on this test's own categories.
     *
     * <p>The test schema is built from migrations and carries no business data, so
     * the real J and D rows do not exist here and neither do the rules the seed
     * would have attached to them. What is worth proving is the BEHAVIOUR — two
     * separately selectable shift categories with different multipliers both
     * resolving to one effective category at coefficient 1 — so the fixture
     * recreates exactly that shape. The seed itself was verified against a clone
     * of the development database.
     *
     * @return the two source categories, in order: the 1.0 one then the 1.2 one
     */
    private List<WorkCodeCategory> shiftCategoriesUnderFixedScheme() {
        int n = COUNTER.incrementAndGet();
        WorkCodeCategory dayShift = category("IT-J-" + n, 1.0d);
        WorkCodeCategory nightShift = category("IT-D-" + n, 1.2d);
        WorkCodeCategory allShifts = foreignAllShifts();

        rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, dayShift, allShifts, true,
                BigDecimal.ONE, RULES_FROM, null);
        rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, nightShift, allShifts, true,
                BigDecimal.ONE, RULES_FROM, null);

        return List.of(dayShift, nightShift);
    }

    private WorkCodeCategorySchemeRule rule(String schemeCode,
                                            WorkCodeCategory source,
                                            WorkCodeCategory effective,
                                            boolean allowed,
                                            BigDecimal override,
                                            LocalDate from,
                                            LocalDate until) {
        return ruleRepository.saveAndFlush(WorkCodeCategorySchemeRule.builder()
                .compensationScheme(scheme(schemeCode))
                .sourceCategory(source)
                .effectiveCategory(effective)
                .isAllowed(allowed)
                .coefficientOverride(override)
                .validFrom(from)
                .validUntil(until)
                .isActive(true)
                .build());
    }

    // ── seeds ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("both schemes are seeded, and only the standard one is open by default")
    void schemesAreSeeded() {
        CompensationScheme standard = scheme(CompensationSchemeCodes.STANDARD);
        CompensationScheme fixed = scheme(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT);

        assertThat(standard.getAllowUnmappedCategories()).isTrue();
        assertThat(fixed.getAllowUnmappedCategories()).isFalse();
        assertThat(standard.isUsable()).isTrue();
        assertThat(fixed.isUsable()).isTrue();
    }

    @Test
    @DisplayName("the migration creates the common effective category unconditionally")
    void foreignAllShiftsCategoryExists() {
        WorkCodeCategory allShifts = foreignAllShifts();

        assertThat(allShifts.getIsActive()).isTrue();
        assertThat(allShifts.getType()).isEqualTo("WORK");
        assertThat(allShifts.getNormMultiplier())
                .as("1.0 so any code path reading the category's own multiplier agrees with the rule override")
                .isEqualTo(1.0d);
    }

    // ── 34.1 scheme history ─────────────────────────────────────────────────

    @Test
    @DisplayName("the scheme is chosen by work date: standard before the transition, foreign on and after it")
    void transitionBoundaryIsInclusiveOnTheNewScheme() {
        Employee employee = anEmployee();
        LocalDate transition = LocalDate.of(2026, 9, 1);
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), transition.minusDays(1));
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, transition, null);

        assertThat(resolutionService.contextFor(employee.getId(), transition.minusDays(1)).scheme().getCode())
                .isEqualTo(CompensationSchemeCodes.STANDARD);
        // The transition date itself already belongs to the new scheme.
        assertThat(resolutionService.contextFor(employee.getId(), transition).scheme().getCode())
                .isEqualTo(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT);
        assertThat(resolutionService.contextFor(employee.getId(), transition.plusDays(1)).scheme().getCode())
                .isEqualTo(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT);
    }

    @Test
    @DisplayName("an open-ended period covers every later date")
    void nullValidUntilIsOpenEnded() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);

        assertThat(resolutionService.contextFor(employee.getId(), LocalDate.of(2099, 12, 31)).scheme().getCode())
                .isEqualTo(CompensationSchemeCodes.STANDARD);
    }

    @Test
    @DisplayName("the database refuses two overlapping periods for the same employee")
    void overlappingPeriodsAreRejected() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThatThrownBy(() ->
                period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT,
                        LocalDate.of(2026, 6, 1), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("periods that merely touch do not overlap")
    void adjacentPeriodsAreAllowed() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31));
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2026, 8, 1), null);

        assertThat(historyRepository.findHistoryFor(employee.getId())).hasSize(2);
    }

    @Test
    @DisplayName("an employee with no scheme for the work date is a clear business error, not a silent standard fallback")
    void missingSchemeIsReported() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        assertThatThrownBy(() -> resolutionService.contextFor(employee.getId(), LocalDate.of(2026, 5, 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("nema definisan način obračuna");
    }

    @Test
    @DisplayName("an archived period is not treated as active")
    void archivedPeriodIsIgnored() {
        Employee employee = anEmployee();
        EmployeeCompensationSchemeHistory archived =
                period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2026, 1, 1), null);
        archived.setArchivedAt(java.time.OffsetDateTime.now());
        historyRepository.saveAndFlush(archived);

        assertThatThrownBy(() -> resolutionService.contextFor(employee.getId(), WORKDAY))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an inactive scheme cannot be used even when a period points at it")
    void inactiveSchemeIsRejected() {
        Employee employee = anEmployee();
        CompensationScheme retired = schemeRepository.saveAndFlush(CompensationScheme.builder()
                .code("IT-RETIRED-" + COUNTER.incrementAndGet())
                .name("Retired scheme")
                .allowUnmappedCategories(true)
                .isActive(true)
                .build());

        historyRepository.saveAndFlush(EmployeeCompensationSchemeHistory.builder()
                .employee(employee)
                .compensationScheme(retired)
                .validFrom(LocalDate.of(2026, 1, 1))
                .build());

        retired.setIsActive(false);
        schemeRepository.saveAndFlush(retired);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> resolutionService.contextFor(employee.getId(), WORKDAY))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("nije aktivan");
    }

    // ── 34.2 category availability ──────────────────────────────────────────

    @Test
    @DisplayName("a standard employee may use a category that has no scheme rule at all")
    void standardSchemeAllowsUnmappedCategories() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory unmapped = category("IT-UNMAPPED-" + COUNTER.incrementAndGet(), 1.4d);

        WorkCategoryResolution resolution =
                resolutionService.resolve(employee.getId(), WORKDAY, unmapped.getId());

        assertThat(resolution.allowed()).isTrue();
        assertThat(resolution.resolutionReason())
                .isEqualTo(WorkCategoryResolution.Reason.SCHEME_DEFAULT_ALLOWS);
        assertThat(resolution.effectiveCategoryId()).isEqualTo(unmapped.getId());
        assertThat(resolution.coefficient()).isEqualByComparingTo("1.4");
        assertThat(resolution.coefficientOverridden()).isFalse();
        assertThat(resolution.schemeRuleId()).isNull();
    }

    @Test
    @DisplayName("a fixed-coefficient employee may NOT use a category that has no scheme rule")
    void closedSchemeRejectsUnmappedCategories() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory unmapped = category("IT-UNMAPPED-" + COUNTER.incrementAndGet(), 1.4d);

        WorkCategoryResolution resolution =
                resolutionService.resolve(employee.getId(), WORKDAY, unmapped.getId());

        assertThat(resolution.allowed()).isFalse();
        assertThat(resolution.resolutionReason())
                .isEqualTo(WorkCategoryResolution.Reason.NO_RULE_AND_SCHEME_CLOSED);
        assertThat(resolution.coefficient()).isNull();
    }

    @Test
    @DisplayName("the backend rejects a directly submitted category the scheme does not allow")
    void directSubmissionOfDisallowedCategoryIsRejected() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory unmapped = category("IT-UNMAPPED-" + COUNTER.incrementAndGet(), 1.4d);

        assertThatThrownBy(() ->
                resolutionService.requireAllowed(employee.getId(), WORKDAY, unmapped.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nije dozvoljena");
    }

    @Test
    @DisplayName("an explicit deny rule beats the scheme's open default")
    void explicitDenyWins() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory denied = category("IT-DENIED-" + COUNTER.incrementAndGet(), 1.0d);
        rule(CompensationSchemeCodes.STANDARD, denied, null, false, null, RULES_FROM, null);

        WorkCategoryResolution resolution =
                resolutionService.resolve(employee.getId(), WORKDAY, denied.getId());

        assertThat(resolution.allowed()).isFalse();
        assertThat(resolution.resolutionReason())
                .isEqualTo(WorkCategoryResolution.Reason.EXPLICIT_RULE_DENIES);
    }

    @Test
    @DisplayName("an inactive rule is ignored and the scheme default applies again")
    void inactiveRuleIsIgnored() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory source = category("IT-INACTIVE-RULE-" + COUNTER.incrementAndGet(), 1.3d);
        WorkCodeCategorySchemeRule saved = rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT,
                source, null, true, BigDecimal.ONE, RULES_FROM, null);

        saved.setIsActive(false);
        ruleRepository.saveAndFlush(saved);
        entityManager.clear();

        // The scheme is closed, so with its only rule inactive the category is gone.
        assertThat(resolutionService.resolve(employee.getId(), WORKDAY, source.getId()).allowed())
                .isFalse();
    }

    @Test
    @DisplayName("a rule outside its validity window is ignored on both sides")
    void ruleValidityWindowIsRespected() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory source = category("IT-WINDOW-" + COUNTER.incrementAndGet(), 1.3d);
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate until = LocalDate.of(2026, 9, 30);
        rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, source, null, true,
                BigDecimal.ONE, from, until);

        assertThat(resolutionService.resolve(employee.getId(), from.minusDays(1), source.getId()).allowed())
                .as("future rule is ignored before it starts").isFalse();
        assertThat(resolutionService.resolve(employee.getId(), from, source.getId()).allowed())
                .as("first day is inclusive").isTrue();
        assertThat(resolutionService.resolve(employee.getId(), until, source.getId()).allowed())
                .as("last day is inclusive").isTrue();
        assertThat(resolutionService.resolve(employee.getId(), until.plusDays(1), source.getId()).allowed())
                .as("expired rule is ignored").isFalse();
    }

    @Test
    @DisplayName("the allowed list offers each source category separately, not the common effective category")
    void allowedListReturnsSourceCategories() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        List<WorkCodeCategory> shifts = shiftCategoriesUnderFixedScheme();

        List<WorkCategoryResolution> allowed =
                resolutionService.listAllowedCategories(employee.getId(), WORKDAY);
        List<String> codes = allowed.stream().map(WorkCategoryResolution::sourceCategoryCode).toList();

        assertThat(codes)
                .as("both shifts stay separately selectable — the employee records what they really worked")
                .contains(shifts.get(0).getCategoryNo(), shifts.get(1).getCategoryNo());
        assertThat(codes)
                .as("the calculation target is not something anyone worked, so it is not selectable")
                .doesNotContain("FOREIGN_ALL_SHIFTS");
        assertThat(allowed).allSatisfy(r -> assertThat(r.allowed()).isTrue());
        assertThat(allowed)
                .as("every entry carries the effective category and coefficient it resolves to")
                .allSatisfy(r -> {
                    assertThat(r.effectiveCategoryCode()).isEqualTo("FOREIGN_ALL_SHIFTS");
                    assertThat(r.coefficient()).isEqualByComparingTo("1");
                });
    }

    @Test
    @DisplayName("an inactive or archived category never appears in the allowed list")
    void inactiveCategoriesAreExcludedFromTheList() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);

        WorkCodeCategory inactive = category("IT-OFF-" + COUNTER.incrementAndGet(), 1.0d);
        inactive.setIsActive(false);
        categoryRepository.saveAndFlush(inactive);
        entityManager.clear();

        assertThat(resolutionService.listAllowedCategories(employee.getId(), WORKDAY))
                .extracting(WorkCategoryResolution::sourceCategoryId)
                .doesNotContain(inactive.getId());
    }

    // ── 34.3 / 34.4 coefficient and effective category ──────────────────────

    @Test
    @DisplayName("standard employees keep each category's own coefficient, unchanged")
    void standardCoefficientsAreUnchanged() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);

        int n = COUNTER.incrementAndGet();
        List<WorkCodeCategory> categories = List.of(
                category("IT-STD-1-" + n, 1.0d),
                category("IT-STD-2-" + n, 1.2d),
                category("IT-STD-3-" + n, 1.1d));

        var context = resolutionService.contextFor(employee.getId(), WORKDAY);
        for (WorkCodeCategory category : categories) {
            WorkCategoryResolution resolution = context.resolveFor(category);

            assertThat(resolution.allowed()).isTrue();
            assertThat(resolution.effectiveCategoryId())
                    .as("no remap under the standard scheme")
                    .isEqualTo(category.getId());
            assertThat(resolution.coefficient())
                    .as(category.getCategoryNo())
                    .isEqualByComparingTo(BigDecimal.valueOf(category.getNormMultiplier()));
            assertThat(resolution.coefficientOverridden()).isFalse();
        }
    }

    @Test
    @DisplayName("every shift category resolves to coefficient 1 and the common category under the fixed scheme")
    void fixedSchemeResolvesEveryShiftToOne() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        List<WorkCodeCategory> shifts = shiftCategoriesUnderFixedScheme();

        var context = resolutionService.contextFor(employee.getId(), WORKDAY);
        for (WorkCodeCategory source : shifts) {
            WorkCategoryResolution resolution = context.resolveFor(source);

            assertThat(resolution.allowed()).isTrue();
            assertThat(resolution.coefficient())
                    .as("%s has multiplier %s but the scheme fixes it at 1",
                            source.getCategoryNo(), source.getNormMultiplier())
                    .isEqualByComparingTo("1");
            assertThat(resolution.coefficientOverridden()).isTrue();
            assertThat(resolution.effectiveCategoryCode()).isEqualTo("FOREIGN_ALL_SHIFTS");
            assertThat(resolution.isCategoryRemapped()).isTrue();
            assertThat(resolution.sourceCategoryCode())
                    .as("the source category is never erased by the effective one")
                    .isEqualTo(source.getCategoryNo());
            assertThat(resolution.schemeRuleId()).isNotNull();
        }
    }

    @Test
    @DisplayName("the same category is worth 1.2 under the standard scheme and 1 under the fixed one")
    void sameCategoryDiffersByScheme() {
        WorkCodeCategory nightShift = shiftCategoriesUnderFixedScheme().get(1);
        assertThat(nightShift.getNormMultiplier()).isEqualTo(1.2d);

        Employee standardEmployee = anEmployee();
        period(standardEmployee, CompensationSchemeCodes.STANDARD, LocalDate.of(2020, 1, 1), null);
        Employee fixedEmployee = anEmployee();
        period(fixedEmployee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);

        assertThat(resolutionService.resolve(standardEmployee.getId(), WORKDAY, nightShift.getId()).coefficient())
                .isEqualByComparingTo("1.2");
        assertThat(resolutionService.resolve(fixedEmployee.getId(), WORKDAY, nightShift.getId()).coefficient())
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a null override falls through to the existing coefficient logic")
    void nullOverrideUsesNormalLogic() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory source = category("IT-PASSTHRU-" + COUNTER.incrementAndGet(), 1.35d);
        rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, source, null, true, null, RULES_FROM, null);

        WorkCategoryResolution resolution =
                resolutionService.resolve(employee.getId(), WORKDAY, source.getId());

        assertThat(resolution.allowed()).isTrue();
        assertThat(resolution.coefficientOverridden()).isFalse();
        assertThat(resolution.coefficient()).isEqualByComparingTo("1.35");
        assertThat(resolution.effectiveCategoryId())
                .as("a null effective category means the effective category IS the source")
                .isEqualTo(source.getId());
    }

    @Test
    @DisplayName("a positive decimal override survives the round trip at the column's scale")
    void decimalOverrideRoundTrips() {
        Employee employee = anEmployee();
        period(employee, CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, LocalDate.of(2020, 1, 1), null);
        WorkCodeCategory source = category("IT-DEC-" + COUNTER.incrementAndGet(), 1.0d);
        rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, source, null, true,
                new BigDecimal("1.25"), RULES_FROM, null);
        entityManager.clear();

        BigDecimal coefficient =
                resolutionService.resolve(employee.getId(), WORKDAY, source.getId()).coefficient();

        assertThat(coefficient).isEqualByComparingTo("1.25");
        assertThat(coefficient.scale())
                .as("NUMERIC(10,2), matching work_logs.norm_multiplier_snapshot")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a non-positive override is rejected by the database")
    void nonPositiveOverrideIsRejected() {
        WorkCodeCategory source = category("IT-BADCOEF-" + COUNTER.incrementAndGet(), 1.0d);

        assertThatThrownBy(() -> rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT,
                source, null, true, BigDecimal.ZERO, RULES_FROM, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the database refuses two overlapping active rules for the same scheme and category")
    void overlappingRulesAreRejected() {
        WorkCodeCategory source = category("IT-DUPRULE-" + COUNTER.incrementAndGet(), 1.0d);
        rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT, source, null, true,
                BigDecimal.ONE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThatThrownBy(() -> rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT,
                source, null, true, BigDecimal.ONE, LocalDate.of(2026, 6, 1), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a rule with valid_until before valid_from is rejected")
    void invertedRuleWindowIsRejected() {
        WorkCodeCategory source = category("IT-INVERTED-" + COUNTER.incrementAndGet(), 1.0d);

        assertThatThrownBy(() -> rule(CompensationSchemeCodes.FOREIGN_FIXED_COEFFICIENT,
                source, null, true, BigDecimal.ONE,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
