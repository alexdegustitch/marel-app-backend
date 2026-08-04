package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.payroll_maintenance.PayrollConfigurationFinding;
import com.aleksandarparipovic.marel_app.payroll_maintenance.PayrollConfigurationReport;
import com.aleksandarparipovic.marel_app.payroll_maintenance.PayrollConfigurationValidationService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The report that finds a configuration gap before somebody runs payroll.
 *
 * <p>Owed since Phase 5. The rules were already enforced — the migration raises
 * on an incomplete matrix and the resolver throws at calculation time — so this
 * changes nothing about what the system permits. What was missing is finding the
 * gap for the whole factory at once, in words, instead of one employee at a time
 * as somebody opens their payroll and meets an exception.
 *
 * <p>EVERY CASE HERE IS BUILT BY BREAKING SOMETHING, then asserting the report
 * names it. A validation service tested only against valid data proves that it
 * returns nothing, which it would also do if it were empty.
 */
@Transactional
class PayrollConfigurationValidationIT extends AbstractIntegrationTest {

    @Autowired private PayrollConfigurationValidationService validationService;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager entityManager;

    private PayrollConfigurationReport report() {
        entityManager.flush();
        entityManager.clear();
        return validationService.validate();
    }

    private static boolean has(PayrollConfigurationReport report, String code) {
        return report.findings().stream().anyMatch(f -> code.equals(f.code()));
    }

    @Test
    @DisplayName("a complete configuration reports nothing that blocks a payroll")
    void aCompleteConfigurationIsClean() {
        // A SCENARIO FIRST, in every test here. The categories, schemes and rules
        // are seeded per scenario — without one the tables are empty, and a
        // validation service run against nothing reports nothing, which is also
        // what a broken one would do.
        fixture.scenario().build();

        PayrollConfigurationReport report = report();

        // The reference data is production's own configuration. If this fails, the
        // report is either wrong or has found something real; both are worth
        // stopping for.
        assertThat(report.blocking())
                .as("blocking findings in the seeded configuration: %s", report.findings())
                .isZero();
        assertThat(report.isPayrollRunnable()).isTrue();
    }

    @Test
    @DisplayName("a category with no rule for an active scheme is reported, not discovered at payroll time")
    void aMissingRuleIsReported() {
        fixture.scenario().build();
        // The exact shape of a new category added on a Tuesday: active, and no
        // rule anywhere. The resolver throws for every employee on every scheme.
        entityManager.createNativeQuery("""
                INSERT INTO payroll_adjustment_categories
                    (code, name, section_code, section_order, sort_order, impact_code,
                     is_manual, allow_negative, is_active, visible_in_ui, visible_in_pdf,
                     show_name, allow_override, override_target, editable_input,
                     allow_total_override, show_when_zero)
                VALUES ('IT_NEW_CATEGORY', 'Nova stavka', 'ADDITIONS', 1, 999, 'GROSS_PLUS',
                        TRUE, FALSE, TRUE, TRUE, TRUE, TRUE, FALSE, 'AMOUNT', 'AMOUNT',
                        FALSE, TRUE)
                """).executeUpdate();

        PayrollConfigurationReport report = report();

        assertThat(has(report, "MISSING_SCHEME_RULE")).isTrue();
        assertThat(report.findings())
                .filteredOn(f -> "MISSING_SCHEME_RULE".equals(f.code()))
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(PayrollConfigurationFinding.Severity.BLOCKING);
                    assertThat(f.subject()).contains("IT_NEW_CATEGORY");
                    // The message has to say what it will cause. "Missing rule" is
                    // a fact; "payroll will refuse to calculate" is why anybody
                    // should stop what they are doing.
                    assertThat(f.message()).contains("Obračun će odbiti");
                });
        assertThat(report.isPayrollRunnable()).isFalse();
    }

    @Test
    @DisplayName("a calculation key nobody implements is reported before it throws")
    void anUnknownCalculationKeyIsReported() {
        fixture.scenario().build();
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_categories
                SET calculation_key = 'IT_NO_SUCH_CALCULATOR'
                WHERE code = 'MEAL_ALLOWANCE'
                """).executeUpdate();

        PayrollConfigurationReport report = report();

        assertThat(has(report, "UNKNOWN_CALCULATION_KEY")).isTrue();
        assertThat(report.findings())
                .filteredOn(f -> "UNKNOWN_CALCULATION_KEY".equals(f.code()))
                .isNotEmpty()
                .allSatisfy(f -> {
                    // The known keys are listed: the fix is picking one, and making
                    // the reader go and find the list is the difference between a
                    // report and a complaint.
                    assertThat(f.message()).contains("MEAL_BY_ELIGIBLE_SHIFTS");
                });
    }

    @Test
    @DisplayName("a required manual input with no editable field can never be locked, and is reported")
    void aRequiredInputWithNoWayToEnterItIsReported() {
        fixture.scenario().build();
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_category_scheme_rules r
                SET required_manual_input = TRUE, editable_input = 'NONE',
                    allow_total_override = FALSE
                FROM payroll_adjustment_categories c
                WHERE c.id = r.payroll_adjustment_category_id AND c.code = 'INSTALLMENT'
                """).executeUpdate();

        PayrollConfigurationReport report = report();

        assertThat(has(report, "REQUIRED_INPUT_WITH_NO_WAY_TO_ENTER_IT")).isTrue();
        assertThat(report.findings())
                .filteredOn(f -> "REQUIRED_INPUT_WITH_NO_WAY_TO_ENTER_IT".equals(f.code()))
                .allSatisfy(f -> assertThat(f.message()).contains("nikada neće moći zaključati"));
    }

    @Test
    @DisplayName("an edit policy on a category the scheme excludes is a warning, not a blocker")
    void anEditPolicyOnAnExcludedCategoryIsAWarning() {
        fixture.scenario().build();
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_category_scheme_rules r
                SET is_allowed = FALSE, editable_input = 'AMOUNT'
                FROM payroll_adjustment_categories c
                WHERE c.id = r.payroll_adjustment_category_id AND c.code = 'INSTALLMENT'
                """).executeUpdate();

        PayrollConfigurationReport report = report();

        assertThat(has(report, "EDIT_POLICY_ON_EXCLUDED_CATEGORY")).isTrue();
        // Nothing is broken — the server refuses the edit correctly. The field is
        // just offered on screen and cannot work, which is a confusing form, not a
        // wrong payslip.
        assertThat(report.findings())
                .filteredOn(f -> "EDIT_POLICY_ON_EXCLUDED_CATEGORY".equals(f.code()))
                .allSatisfy(f -> assertThat(f.severity())
                        .isEqualTo(PayrollConfigurationFinding.Severity.WARNING));
    }

    @Test
    @DisplayName("an employee with no scheme in force is found for the whole factory at once")
    void anEmployeeWithoutASchemeIsReported() {
        var scenario = fixture.scenario().build();

        // The state that archived 235 items in this database: an employee nobody
        // has assigned. At calculation time it surfaces one employee at a time, as
        // somebody opens their month.
        entityManager.createNativeQuery("""
                UPDATE employee_compensation_scheme_history SET archived_at = now()
                WHERE employee_id = :emp
                """).setParameter("emp", scenario.employee().getId()).executeUpdate();

        PayrollConfigurationReport report = report();

        assertThat(report.findings())
                .filteredOn(f -> "EMPLOYEE_WITHOUT_A_SCHEME".equals(f.code()))
                .isNotEmpty()
                .anySatisfy(f -> assertThat(f.subject())
                        .contains(String.valueOf(scenario.employee().getId())));
        assertThat(report.isPayrollRunnable()).isFalse();
    }

    @Test
    @DisplayName("two overlapping scheme periods are the database's job, not this report's")
    void overlappingPeriodsAreRefusedBySchemaNotReported() {
        var scenario = fixture.scenario().build();

        // ex_ecsh_no_overlap is an EXCLUDE constraint: two periods covering one day
        // cannot be STORED. A report that looked for them would be telling its
        // reader they can happen, so it does not — and this is the assertion that
        // the premise still holds.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                    INSERT INTO employee_compensation_scheme_history
                        (employee_id, compensation_scheme_id, valid_from, created_at)
                    SELECT :emp, h.compensation_scheme_id, h.valid_from + INTERVAL '2 months', now()
                    FROM employee_compensation_scheme_history h
                    WHERE h.employee_id = :emp AND h.archived_at IS NULL
                    LIMIT 1
                    """).setParameter("emp", scenario.employee().getId()).executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("ex_ecsh_no_overlap");
    }

    @Test
    @DisplayName("findings come back worst first, so the top of the list is what to fix")
    void findingsAreOrderedBySeverity() {
        fixture.scenario().build();
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_category_scheme_rules r
                SET is_allowed = FALSE, editable_input = 'AMOUNT'
                FROM payroll_adjustment_categories c
                WHERE c.id = r.payroll_adjustment_category_id AND c.code = 'INSTALLMENT'
                """).executeUpdate();
        entityManager.createNativeQuery("""
                UPDATE payroll_adjustment_categories
                SET calculation_key = 'IT_NO_SUCH_CALCULATOR'
                WHERE code = 'MEAL_ALLOWANCE'
                """).executeUpdate();

        PayrollConfigurationReport report = report();

        assertThat(report.blocking()).isPositive();
        assertThat(report.warnings()).isPositive();
        assertThat(report.findings().getFirst().severity())
                .isEqualTo(PayrollConfigurationFinding.Severity.BLOCKING);
    }
}
