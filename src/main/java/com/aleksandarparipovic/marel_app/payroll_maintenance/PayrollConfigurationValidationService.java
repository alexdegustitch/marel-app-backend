package com.aleksandarparipovic.marel_app.payroll_maintenance;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationSchemeRepository;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRule;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRuleRepository;
import com.aleksandarparipovic.marel_app.payroll_calculation.PayrollCalculatorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the configuration gaps that would otherwise be found by running payroll.
 *
 * <p>WHY THIS EXISTS. The rules are already enforced — the migration raises on an
 * incomplete matrix and {@code PayrollSchemeScopeService} throws at calculation
 * time — so nothing here changes what the system permits. What was missing is the
 * report that finds the gap BEFORE somebody opens a payroll month and meets an
 * exception with an employee's name in it. Owed since Phase 5.
 *
 * <p>IT REPORTS, IT DOES NOT REFUSE. An administrator adding a category on a
 * Tuesday should see "these four schemes have no rule for it yet" rather than be
 * blocked from saving; the calculation is what must be strict, and it is.
 *
 * <p>BLOCKING means payroll will refuse or misprice today. WARNING means a gap
 * that has not bitten yet — an inactive scheme still assigned to somebody whose
 * next month is not yet created, for instance.
 */
@Service
@RequiredArgsConstructor
public class PayrollConfigurationValidationService {

    private final CompensationSchemeRepository compensationSchemeRepository;
    private final PayrollAdjustmentCategoryRepository categoryRepository;
    private final PayrollAdjustmentCategorySchemeRuleRepository ruleRepository;
    private final EmployeeCompensationSchemeHistoryRepository schemeHistoryRepository;
    private final PayrollCalculatorRegistry calculatorRegistry;
    private final EmployeeRepository employeeRepository;

    private static final Set<String> KNOWN_MODES = Set.of("INHERIT", "ZERO", "MANUAL");
    private static final Set<String> KNOWN_INPUTS = Set.of("NONE", "AMOUNT", "UNIT_AMOUNT",
            "QUANTITY", "CORRECTION");

    @Transactional(readOnly = true)
    public PayrollConfigurationReport validate() {
        List<PayrollConfigurationFinding> findings = new ArrayList<>();

        List<CompensationScheme> schemes = compensationSchemeRepository.findAll().stream()
                .filter(s -> s.getArchivedAt() == null)
                .toList();
        List<PayrollAdjustmentCategory> categories = categoryRepository.findAll().stream()
                .filter(c -> c.getArchivedAt() == null)
                .toList();
        List<PayrollAdjustmentCategorySchemeRule> rules = ruleRepository.findAll().stream()
                .filter(r -> r.getArchivedAt() == null)
                .toList();

        missingRules(schemes, categories, rules, findings);
        unknownCalculationKeys(categories, rules, findings);
        illegalEditPolicies(rules, findings);
        inactiveConfigurationStillInUse(schemes, findings);
        schemeAssignmentProblems(findings);

        findings.sort(Comparator
                .comparing((PayrollConfigurationFinding f) -> f.severity().ordinal())
                .thenComparing(PayrollConfigurationFinding::code)
                .thenComparing(PayrollConfigurationFinding::subject));

        return new PayrollConfigurationReport(
                (int) findings.stream()
                        .filter(f -> f.severity() == PayrollConfigurationFinding.Severity.BLOCKING).count(),
                (int) findings.stream()
                        .filter(f -> f.severity() == PayrollConfigurationFinding.Severity.WARNING).count(),
                findings);
    }

    /**
     * Every active scheme needs a rule for every active category.
     *
     * <p>A missing rule is not "no restriction": the resolver throws rather than
     * guess, so this is the difference between a payroll month opening and an
     * exception naming an employee who did nothing wrong.
     */
    private void missingRules(List<CompensationScheme> schemes,
                              List<PayrollAdjustmentCategory> categories,
                              List<PayrollAdjustmentCategorySchemeRule> rules,
                              List<PayrollConfigurationFinding> out) {
        Set<String> present = new HashSet<>();
        rules.forEach(r -> present.add(
                r.getCompensationScheme().getId() + ":" + r.getPayrollAdjustmentCategory().getId()));

        for (CompensationScheme scheme : schemes) {
            if (!Boolean.TRUE.equals(scheme.getIsActive())) {
                continue;
            }
            for (PayrollAdjustmentCategory category : categories) {
                if (!Boolean.TRUE.equals(category.getIsActive())) {
                    continue;
                }
                if (!present.contains(scheme.getId() + ":" + category.getId())) {
                    out.add(PayrollConfigurationFinding.blocking(
                            "MISSING_SCHEME_RULE",
                            scheme.getCode() + " × " + category.getCode(),
                            "Način obračuna \"" + scheme.getCode() + "\" nema pravilo za stavku \""
                                    + category.getCode() + "\". Obračun će odbiti da se izračuna za "
                                    + "svakog zaposlenog na ovom načinu obračuna."));
                }
            }
        }
    }

    /**
     * A calculation_key nobody implements.
     *
     * <p>An unknown key is a hard error at calculation time by design — never a
     * silent zero — so it is worth finding before the month opens. The scheme's
     * own key wins where it sets one, which is why both are checked.
     */
    private void unknownCalculationKeys(List<PayrollAdjustmentCategory> categories,
                                        List<PayrollAdjustmentCategorySchemeRule> rules,
                                        List<PayrollConfigurationFinding> out) {
        for (PayrollAdjustmentCategory category : categories) {
            String key = category.getCalculationKey();
            if (key != null && !key.isBlank() && !"MANUAL".equals(key)
                    && !calculatorRegistry.knows(key)) {
                out.add(PayrollConfigurationFinding.blocking(
                        "UNKNOWN_CALCULATION_KEY",
                        category.getCode(),
                        "Stavka \"" + category.getCode() + "\" traži proračun \"" + key
                                + "\", koji ne postoji. Poznati su: "
                                + String.join(", ", calculatorRegistry.knownKeys()) + "."));
            }
        }

        for (PayrollAdjustmentCategorySchemeRule rule : rules) {
            String mode = rule.getCalculationMode();
            if (mode != null && !KNOWN_MODES.contains(mode)) {
                out.add(PayrollConfigurationFinding.blocking(
                        "UNKNOWN_CALCULATION_MODE",
                        subjectOf(rule),
                        "Pravilo koristi način računanja \"" + mode + "\", koji ne postoji. "
                                + "Dozvoljeni su: INHERIT, ZERO, MANUAL."));
            }
        }
    }

    /**
     * Edit policies that contradict themselves.
     *
     * <p>Each of these is a line an administrator can see on screen and cannot
     * make behave: a field the form offers and the server refuses, or a required
     * input with no way to enter it. They are silent — nothing throws — which is
     * exactly why they need a report.
     */
    private void illegalEditPolicies(List<PayrollAdjustmentCategorySchemeRule> rules,
                                     List<PayrollConfigurationFinding> out) {
        for (PayrollAdjustmentCategorySchemeRule rule : rules) {
            String input = effectiveEditableInput(rule);
            String subject = subjectOf(rule);

            if (input != null && !KNOWN_INPUTS.contains(input)) {
                out.add(PayrollConfigurationFinding.blocking(
                        "UNKNOWN_EDITABLE_INPUT", subject,
                        "Pravilo dozvoljava izmenu polja \"" + input + "\", koje ne postoji. "
                                + "Dozvoljena su: " + String.join(", ", KNOWN_INPUTS) + "."));
            }

            boolean excluded = !Boolean.TRUE.equals(rule.getIsAllowed());
            boolean forcedZero = "ZERO".equals(rule.getCalculationMode());
            boolean offersAnInput = input != null && !"NONE".equals(input);

            if (excluded && (offersAnInput || effectiveAllowTotalOverride(rule))) {
                out.add(PayrollConfigurationFinding.warning(
                        "EDIT_POLICY_ON_EXCLUDED_CATEGORY", subject,
                        "Stavka ne pripada ovom načinu obračuna, ali pravilo i dalje dozvoljava "
                                + "izmenu. Izmena će biti odbijena — polje samo zbunjuje."));
            }
            if (forcedZero && (offersAnInput || effectiveAllowTotalOverride(rule))) {
                out.add(PayrollConfigurationFinding.warning(
                        "EDIT_POLICY_ON_FORCED_ZERO", subject,
                        "Stavka je po ovom načinu obračuna uvek nula, ali pravilo dozvoljava "
                                + "izmenu. Izmena će biti odbijena."));
            }
            if (Boolean.TRUE.equals(rule.getRequiredManualInput()) && !offersAnInput
                    && !effectiveAllowTotalOverride(rule)) {
                out.add(PayrollConfigurationFinding.blocking(
                        "REQUIRED_INPUT_WITH_NO_WAY_TO_ENTER_IT", subject,
                        "Pravilo traži ručni unos, a ne dozvoljava izmenu nijednog polja. "
                                + "Obračun se nikada neće moći zaključati."));
            }
        }
    }

    // THERE IS NO "MANUAL COMPONENT WITH NO INPUT" CHECK, and the first draft's
    // version of it is why.
    //
    // It flagged 33 scheme × category pairs in the seeded configuration, every one
    // of them correct as configured. Two reasons, both worth keeping written down:
    //
    //   * A rule's editable_input is NULL when it inherits the CATEGORY's, which
    //     is the normal case — reading the rule alone makes almost everything look
    //     uneditable. The resolver has always combined the two; the check did not.
    //   * MANUAL means "no calculator", not "no value". PAID_PREVIOUS_PERIOD and
    //     PREVIOUS_BALANCE are MANUAL with no editable input at all, and are filled
    //     by writeDerivedSettlementLines. Nothing distinguishes them from a line
    //     nobody can fill, so the check cannot be made honest.
    //
    // A report whose first run shows 33 blockers on a correct configuration is
    // worse than no report: it teaches its reader to close it. Same lesson the
    // step-3 diagnostic left — a check that cannot reach zero is not a check.

    /** An inactive scheme somebody is still on. */
    private void inactiveConfigurationStillInUse(List<CompensationScheme> schemes,
                                                 List<PayrollConfigurationFinding> out) {
        for (CompensationScheme scheme : schemes) {
            if (Boolean.TRUE.equals(scheme.getIsActive())) {
                continue;
            }
            long assigned = schemeHistoryRepository.findAll().stream()
                    .filter(h -> h.getArchivedAt() == null)
                    .filter(h -> h.getCompensationScheme() != null
                            && h.getCompensationScheme().getId().equals(scheme.getId()))
                    .filter(h -> h.getValidUntil() == null
                            || !h.getValidUntil().isBefore(LocalDate.now()))
                    .count();
            if (assigned > 0) {
                out.add(PayrollConfigurationFinding.warning(
                        "INACTIVE_SCHEME_STILL_ASSIGNED", scheme.getCode(),
                        assigned + " zaposlen(ih) je i dalje na neaktivnom načinu obračuna \""
                                + scheme.getCode() + "\"."));
            }
        }
    }

    /**
     * D1, checked ahead of time: an employee with NO scheme in force.
     *
     * <p>Zero and more than one are both errors at calculation time. MORE THAN ONE
     * IS NOT CHECKED HERE, and deliberately: {@code ex_ecsh_no_overlap} is an
     * EXCLUDE constraint, so the database refuses to store two periods covering
     * one day. A report that looked for it would be telling its reader that it can
     * happen.
     *
     * <p>Zero is different — it is the ordinary state of an employee nobody has
     * assigned yet, it stops their payroll, and 235 items were archived for it in
     * this database. Finding it here means finding it for the whole factory at
     * once, in a list, rather than one employee at a time as somebody opens their
     * month.
     */
    private void schemeAssignmentProblems(List<PayrollConfigurationFinding> out) {
        LocalDate today = LocalDate.now();

        Set<Long> covered = schemeHistoryRepository.findAll().stream()
                .filter(h -> h.getArchivedAt() == null)
                .filter(h -> !h.getValidFrom().isAfter(today))
                .filter(h -> h.getValidUntil() == null || !h.getValidUntil().isBefore(today))
                .map(h -> h.getEmployee().getId())
                .collect(java.util.stream.Collectors.toSet());

        employeeRepository.findAll().stream()
                .filter(e -> e.getArchivedAt() == null)
                .filter(e -> !covered.contains(e.getId()))
                .forEach(e -> out.add(PayrollConfigurationFinding.blocking(
                        "EMPLOYEE_WITHOUT_A_SCHEME",
                        "employee " + e.getId() + " — " + e.getFullName(),
                        "Zaposleni \"" + e.getFullName() + "\" nema način obračuna koji važi danas. "
                                + "Obračun za njega neće moći da se izračuna.")));
    }

    /**
     * What the rule actually says, not what its own column holds.
     *
     * <p>A NULL on the rule means "inherit the category's", which is the normal
     * case — reading the rule's column alone made 33 correctly configured pairs
     * look uneditable in the first draft of this service.
     */
    private static String effectiveEditableInput(PayrollAdjustmentCategorySchemeRule rule) {
        return rule.getEditableInput() != null
                ? rule.getEditableInput()
                : rule.getPayrollAdjustmentCategory().getEditableInput();
    }

    private static boolean effectiveAllowTotalOverride(PayrollAdjustmentCategorySchemeRule rule) {
        return rule.getAllowTotalOverride() != null
                ? rule.getAllowTotalOverride()
                : Boolean.TRUE.equals(rule.getPayrollAdjustmentCategory().getAllowTotalOverride());
    }

    private static String subjectOf(PayrollAdjustmentCategorySchemeRule rule) {
        return rule.getCompensationScheme().getCode() + " × "
                + rule.getPayrollAdjustmentCategory().getCode();
    }
}
