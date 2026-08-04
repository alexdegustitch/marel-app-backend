package com.aleksandarparipovic.marel_app.work_category_resolution;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRule;
import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategorySchemeRuleRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which categories and adjustment lines may appear on a payslip.
 *
 * <p>Answers the payroll-period question, as opposed to
 * {@link WorkCategoryResolutionService}, which answers the work-date question.
 * Both read the same tables; they differ in that this one answers for a whole
 * month rather than for a date.
 *
 * <p><b>Exactly one scheme per month, or an error.</b> This used to union every
 * scheme overlapping the month, because a mid-month change could otherwise leave
 * recorded work with no payroll row. Mid-month changes are now forbidden at the
 * source, so the union is gone and with it the leak it caused — a restricted
 * employee inheriting a permission from the scheme they left.
 *
 * <p><b>Batched by design.</b> A payroll run initialises every employee at once,
 * so this resolves a whole batch with a fixed number of queries rather than a
 * few per employee.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollSchemeScopeService {

    private final EmployeeCompensationSchemeHistoryRepository schemeHistoryRepository;
    private final WorkCodeCategorySchemeRuleRepository workRuleRepository;
    private final PayrollAdjustmentCategorySchemeRuleRepository adjustmentRuleRepository;
    private final com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository workCodeCategoryRepository;
    private final com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategoryRepository adjustmentCategoryRepository;

    /**
     * Resolve the scope for many employees over one payroll period.
     *
     * <p><b>Exactly one scheme per employee per month (D1).</b> Zero is an error
     * and so is more than one — neither is defaulted, because every plausible
     * default is wrong in a way nobody notices. Guessing "unrestricted" pays money
     * the policy may forbid; guessing "the first one we found" is arbitrary; and
     * merging two schemes, which is what this method used to do, lets a restricted
     * employee inherit a permission from the scheme they left.
     *
     * <p>A scheme change now takes effect on the first day of the following month,
     * so a month spanning two schemes cannot be created through the application at
     * all. If one exists, it predates that rule and needs a person to look at it.
     */
    @Transactional(readOnly = true)
    public Map<Long, PayrollSchemeScope> scopesFor(Collection<Long> employeeIds,
                                                   LocalDate periodStart,
                                                   LocalDate periodEnd,
                                                   List<WorkCodeCategory> allWorkCategories,
                                                   List<PayrollAdjustmentCategory> allAdjustmentCategories) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }

        // One query for the whole batch: every scheme period overlapping the month.
        List<EmployeeCompensationSchemeHistory> periods =
                schemeHistoryRepository.findOverlapping(employeeIds, periodStart, periodEnd);

        Map<Long, Set<CompensationScheme>> schemesByEmployee = new HashMap<>();
        for (EmployeeCompensationSchemeHistory period : periods) {
            schemesByEmployee
                    .computeIfAbsent(period.getEmployee().getId(), id -> new HashSet<>())
                    .add(period.getCompensationScheme());
        }

        // One resolution per distinct scheme, not per employee: a factory has a
        // handful of schemes and hundreds of employees.
        Map<Long, PayrollSchemeScope> scopeByScheme = new HashMap<>();
        Map<Long, PayrollSchemeScope> result = new HashMap<>();

        for (Long employeeId : employeeIds) {
            Set<CompensationScheme> schemes = schemesByEmployee.get(employeeId);

            if (schemes == null || schemes.isEmpty()) {
                throw new IncompletePayrollConfigurationException(
                        "Zaposleni " + employeeId + " nema način obračuna za period "
                                + periodStart + " – " + periodEnd
                                + ". Dodelite mu način obračuna pre obračuna plate.");
            }
            if (schemes.size() > 1) {
                throw new IncompletePayrollConfigurationException(
                        "Zaposleni " + employeeId + " ima " + schemes.size()
                                + " načina obračuna u periodu " + periodStart + " – " + periodEnd
                                + " (" + schemes.stream().map(CompensationScheme::getCode).sorted().toList()
                                + "). Obračunski mesec mora imati tačno jedan.");
            }

            CompensationScheme scheme = schemes.iterator().next();
            result.put(employeeId, scopeByScheme.computeIfAbsent(scheme.getId(), id ->
                    scopeForScheme(scheme, periodStart, periodEnd,
                            allWorkCategories, allAdjustmentCategories)));
        }
        return result;
    }

    /**
     * The scope for one employee over one period.
     *
     * <p>Never {@code null}: it either resolves one scheme or throws
     * {@link IncompletePayrollConfigurationException}. It used to return
     * {@code null} for "no scheme", which callers read as unrestricted — the
     * quietest possible way to pay somebody under a policy nobody chose.
     *
     * <p>For a single item; the payroll run initialiser uses the batched
     * {@link #scopesFor} instead.
     */
    @Transactional(readOnly = true)
    public PayrollSchemeScope scopeFor(Long employeeId, LocalDate periodStart, LocalDate periodEnd) {
        if (employeeId == null || periodStart == null || periodEnd == null) {
            throw new IncompletePayrollConfigurationException(
                    "Ne mogu da odredim način obračuna bez zaposlenog i obračunskog perioda.");
        }
        return scopesFor(List.of(employeeId), periodStart, periodEnd,
                workCodeCategoryRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByDisplayOrderAscIdAsc(),
                adjustmentCategoryRepository.findByIsActiveTrueAndArchivedAtIsNull())
                .get(employeeId);
    }

    private PayrollSchemeScope scopeForScheme(CompensationScheme scheme,
                                              LocalDate periodStart,
                                              LocalDate periodEnd,
                                              List<WorkCodeCategory> allWorkCategories,
                                              List<PayrollAdjustmentCategory> allAdjustmentCategories) {

        // ── Work categories: CLOSED by default when the scheme says so ───────
        List<WorkCodeCategorySchemeRule> workRules =
                workRuleRepository.findInForceForSchemeBetween(scheme.getId(), periodStart, periodEnd);

        // THE QUESTION HERE IS "WHAT CAN LAND ON THE PAYSLIP", NOT "WHAT MAY BE
        // SELECTED". They differ exactly where a rule remaps.
        //
        // Under the fixed-coefficient scheme a supervisor picks J, but the
        // calculation turns it into S, so nothing ever accumulates against J.
        // Listing J on the payslip would put a permanent zero row there for work
        // that by construction cannot land on it. So a source that remaps to a
        // DIFFERENT category is not payable; only its target is.
        //
        // A self-mapping rule (S -> S) is not a remap and stays payable, which is
        // how the target earns its row.
        Map<Long, Boolean> workAllowedByRule = new HashMap<>();
        for (WorkCodeCategorySchemeRule rule : workRules) {
            boolean allowed = Boolean.TRUE.equals(rule.getIsAllowed());
            Long sourceId = rule.getSourceCategory().getId();
            Long effectiveId = rule.getEffectiveCategory() == null
                    ? null : rule.getEffectiveCategory().getId();
            boolean remapsElsewhere = effectiveId != null && !effectiveId.equals(sourceId);

            // Union across the period on both flags: payable if any in-force rule
            // makes it payable.
            workAllowedByRule.merge(sourceId, allowed && !remapsElsewhere, (a, b) -> a || b);

            if (allowed && effectiveId != null) {
                // Where the money actually ends up, whether or not anyone selects it.
                workAllowedByRule.merge(effectiveId, true, (a, b) -> a || b);
            }
        }

        boolean workOpenByDefault = Boolean.TRUE.equals(scheme.getAllowUnmappedCategories());
        Set<Long> allowedWork = new HashSet<>();
        for (WorkCodeCategory category : allWorkCategories) {
            Boolean explicit = workAllowedByRule.get(category.getId());
            if (explicit != null ? explicit : workOpenByDefault) {
                allowedWork.add(category.getId());
            }
        }

        // ── Adjustment lines: EVERY category needs an explicit rule (D6) ─────
        // The old default was ALLOW, chosen because a payslip line that silently
        // disappears is harder to notice than an extra one. It also meant most of
        // what the system does was written down nowhere: 21 of 26 pairs had no row.
        //
        // A missing rule is now an incomplete configuration rather than a third
        // silent default. Migration 2026-08-15-03 fills the matrix, reproducing
        // exactly what ALLOW produced, so nothing changes except that it is stated.
        List<PayrollAdjustmentCategorySchemeRule> adjustmentRules =
                adjustmentRuleRepository.findInForceForSchemeBetween(scheme.getId(), periodStart, periodEnd);

        Map<Long, PayrollAdjustmentCategorySchemeRule> ruleByCategory = new HashMap<>();
        for (PayrollAdjustmentCategorySchemeRule rule : adjustmentRules) {
            ruleByCategory.put(rule.getPayrollAdjustmentCategory().getId(), rule);
        }

        Map<Long, EffectiveComponentConfig> components = new HashMap<>();
        List<String> withoutRule = new ArrayList<>();

        for (PayrollAdjustmentCategory category : allAdjustmentCategories) {
            PayrollAdjustmentCategorySchemeRule rule = ruleByCategory.get(category.getId());
            if (rule == null) {
                withoutRule.add(category.getCode());
                continue;
            }
            components.put(category.getId(), merge(category, rule));
        }

        if (!withoutRule.isEmpty()) {
            withoutRule.sort(String::compareTo);
            throw new IncompletePayrollConfigurationException(
                    "Način obračuna \"" + scheme.getCode() + "\" nema pravilo za stavke: "
                            + withoutRule + " u periodu " + periodStart + " – " + periodEnd
                            + ". Svaka stavka mora imati eksplicitno pravilo.");
        }

        return new PayrollSchemeScope(scheme.getId(), scheme.getCode(), allowedWork, components,
                Boolean.TRUE.equals(scheme.getAllowsPerformanceBonus()));
    }

    /**
     * The rule over the category: every nullable field on the rule means "inherit".
     *
     * <p>Resolved once, here, where both are in hand — rather than at each of the
     * places that ask, which is how two of them end up disagreeing.
     */
    private EffectiveComponentConfig merge(PayrollAdjustmentCategory category,
                                           PayrollAdjustmentCategorySchemeRule rule) {
        return new EffectiveComponentConfig(
                category.getId(),
                category.getCode(),
                Boolean.TRUE.equals(rule.getIsAllowed()),
                rule.getCalculationMode(),
                category.getCalculationKey(),
                firstNonNull(rule.getVisibleInUi(), category.getVisibleInUi(), true),
                firstNonNull(rule.getVisibleInPdf(), category.getVisibleInPdf(), true),
                firstNonNull(rule.getShowWhenZero(), category.getShowWhenZero(), true),
                rule.getEditableInput() != null ? rule.getEditableInput()
                        : (category.getEditableInput() != null ? category.getEditableInput() : "NONE"),
                firstNonNull(rule.getAllowTotalOverride(), category.getAllowTotalOverride(), false),
                firstNonNull(rule.getRequiredManualInput(), category.getRequiredManualInput(), false));
    }

    private static boolean firstNonNull(Boolean fromRule, Boolean fromCategory, boolean fallback) {
        if (fromRule != null) {
            return fromRule;
        }
        return fromCategory != null ? fromCategory : fallback;
    }
}
