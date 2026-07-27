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
 * Both read the same tables; they differ in that a payroll month is a range an
 * employee can change scheme inside. See {@link PayrollSchemeScope} for why
 * every answer here is a union.
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
     * <p>An employee with no scheme period covering any part of the month is
     * omitted from the result. Callers treat that as "no restriction known" and
     * fall back to the full list — payroll initialisation is not the place to
     * refuse an employee, and the work-date resolver will have already rejected
     * any work they tried to record.
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
        for (Set<CompensationScheme> schemes : schemesByEmployee.values()) {
            for (CompensationScheme scheme : schemes) {
                scopeByScheme.computeIfAbsent(scheme.getId(), id ->
                        scopeForScheme(scheme, periodStart, periodEnd,
                                allWorkCategories, allAdjustmentCategories));
            }
        }

        Map<Long, PayrollSchemeScope> result = new HashMap<>();
        schemesByEmployee.forEach((employeeId, schemes) -> {
            PayrollSchemeScope merged = null;
            for (CompensationScheme scheme : schemes) {
                PayrollSchemeScope scope = scopeByScheme.get(scheme.getId());
                merged = merged == null ? scope : union(merged, scope);
            }
            if (merged != null) {
                result.put(employeeId, merged);
            }
        });
        return result;
    }

    /**
     * The scope for one employee over one period.
     *
     * <p>{@code null} when the employee has no scheme period covering any part
     * of it — callers read that as unrestricted.
     *
     * <p>For a single item; the payroll run initialiser uses the batched
     * {@link #scopesFor} instead.
     */
    @Transactional(readOnly = true)
    public PayrollSchemeScope scopeFor(Long employeeId, LocalDate periodStart, LocalDate periodEnd) {
        if (employeeId == null || periodStart == null || periodEnd == null) {
            return null;
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

        Map<Long, Boolean> workAllowedByRule = new HashMap<>();
        for (WorkCodeCategorySchemeRule rule : workRules) {
            // Union across the period: allowed if any in-force rule allows it.
            workAllowedByRule.merge(rule.getSourceCategory().getId(),
                    Boolean.TRUE.equals(rule.getIsAllowed()), (a, b) -> a || b);
            if (rule.getEffectiveCategory() != null && Boolean.TRUE.equals(rule.getIsAllowed())) {
                // The remap target has to be able to appear on the payslip even
                // though nobody selects it — it is where the money lands.
                workAllowedByRule.merge(rule.getEffectiveCategory().getId(), true, (a, b) -> a || b);
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

        // ── Adjustment categories: OPEN by default, always ───────────────────
        // A row exists to deny. See PayrollAdjustmentCategorySchemeRule for why
        // this default is the opposite of the one above.
        List<PayrollAdjustmentCategorySchemeRule> adjustmentRules =
                adjustmentRuleRepository.findInForceForSchemeBetween(scheme.getId(), periodStart, periodEnd);

        Map<Long, Boolean> adjustmentAllowedByRule = new HashMap<>();
        for (PayrollAdjustmentCategorySchemeRule rule : adjustmentRules) {
            adjustmentAllowedByRule.merge(rule.getPayrollAdjustmentCategory().getId(),
                    Boolean.TRUE.equals(rule.getIsAllowed()), (a, b) -> a || b);
        }

        Set<Long> allowedAdjustments = new HashSet<>();
        for (PayrollAdjustmentCategory category : allAdjustmentCategories) {
            if (adjustmentAllowedByRule.getOrDefault(category.getId(), true)) {
                allowedAdjustments.add(category.getId());
            }
        }

        return new PayrollSchemeScope(allowedWork, allowedAdjustments,
                Boolean.TRUE.equals(scheme.getAllowsPerformanceBonus()));
    }

    private PayrollSchemeScope union(PayrollSchemeScope a, PayrollSchemeScope b) {
        Set<Long> work = new HashSet<>(a.allowedWorkCategoryIds());
        work.addAll(b.allowedWorkCategoryIds());
        Set<Long> adjustments = new HashSet<>(a.allowedAdjustmentCategoryIds());
        adjustments.addAll(b.allowedAdjustmentCategoryIds());
        return new PayrollSchemeScope(work, adjustments,
                a.allowsPerformanceBonus() || b.allowsPerformanceBonus());
    }
}
