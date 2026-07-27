package com.aleksandarparipovic.marel_app.work_category_resolution;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule;
import com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.repository.WorkCodeCategorySchemeRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single source of truth for "what does this category mean for this employee
 * on this date".
 *
 * <p>Every entry point — the allowed-category API, work-log validation, work-log
 * creation and editing, the daily recalc engine, payroll — goes through this
 * service or through an immutable snapshot it produced. Nothing anywhere else
 * decides whether a category is allowed or what coefficient it carries, and in
 * particular there is no {@code if (employee.isForeigner())} branch anywhere in
 * the calculation path: the resolution result already says everything a caller
 * needs.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Source category</b> — the category describing the actual work. Taken
 *       as given and never replaced.</li>
 *   <li><b>Scheme</b> — the employee's scheme on the WORK DATE, from
 *       {@code employee_compensation_scheme_history}. Never "now", never the
 *       payroll run date. Exactly one must apply; none and more-than-one are both
 *       reported as clear business errors rather than papered over by falling
 *       back to STANDARD.</li>
 *   <li><b>Rule</b> — the in-force {@code work_code_category_scheme_rules} row
 *       for (scheme, source category, work date). With no rule, the scheme's
 *       {@code allow_unmapped_categories} decides.</li>
 *   <li><b>Coefficient</b> — the rule's {@code coefficient_override} when set,
 *       otherwise the category's own {@code norm_multiplier}. Always
 *       {@link BigDecimal}; never binary floating point.</li>
 * </ol>
 *
 * <h2>Contextual mappings are somebody else's job</h2>
 * This service does not touch {@code work_code_category_mappings}. Night, weekend
 * and parallel-machine derivation continues to be resolved by the recalc engine
 * from the SOURCE category, unchanged. A fixed coefficient must not silently
 * delete a night mapping, so the two run independently and both results are kept.
 *
 * <h2>Batching</h2>
 * Resolving a payroll month one log at a time would issue two queries per log.
 * {@link #contextFor(Long, LocalDate)} loads the scheme and its whole rule set
 * once and answers any number of categories from memory. The single-shot
 * {@link #resolve} is a convenience wrapper over it and is for one-off callers
 * only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkCategoryResolutionService {

    private final EmployeeCompensationSchemeHistoryRepository schemeHistoryRepository;
    private final WorkCodeCategorySchemeRuleRepository schemeRuleRepository;
    private final WorkCodeCategoryRepository workCodeCategoryRepository;

    /**
     * Resolve one (employee, date, source category) triple.
     *
     * <p>Never returns null and never throws because a category is disallowed —
     * a denial is a normal, describable outcome carried in the result. It throws
     * only when the employee's scheme history itself is unusable, which is a
     * configuration fault the caller cannot sensibly continue past.
     */
    @Transactional(readOnly = true)
    public WorkCategoryResolution resolve(Long employeeId, LocalDate workDate, Long sourceCategoryId) {
        return contextFor(employeeId, workDate).resolve(sourceCategoryId);
    }

    /**
     * Load everything needed to resolve any number of categories for one employee
     * on one date.
     *
     * <p>Two queries total, regardless of how many categories are then resolved.
     */
    @Transactional(readOnly = true)
    public ResolutionContext contextFor(Long employeeId, LocalDate workDate) {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(workDate, "workDate");

        CompensationScheme scheme = resolveScheme(employeeId, workDate);
        List<WorkCodeCategorySchemeRule> rules =
                schemeRuleRepository.findActiveForSchemeAt(scheme.getId(), workDate);

        Map<Long, WorkCodeCategorySchemeRule> bySourceCategory = new HashMap<>();
        for (WorkCodeCategorySchemeRule rule : rules) {
            // The exclusion constraint guarantees at most one in-force rule per
            // (scheme, source category), so a duplicate here would mean the
            // constraint was dropped. Keep the earliest-starting one and say so
            // rather than picking silently at random.
            WorkCodeCategorySchemeRule previous =
                    bySourceCategory.put(rule.getSourceCategory().getId(), rule);
            if (previous != null) {
                log.warn("Overlapping scheme rules for scheme={} sourceCategory={} on {} (rule ids {} and {});"
                                + " ex_wccsr_no_overlap should have prevented this",
                        scheme.getCode(), rule.getSourceCategory().getId(), workDate,
                        previous.getId(), rule.getId());
                if (previous.getValidFrom().isBefore(rule.getValidFrom())) {
                    bySourceCategory.put(rule.getSourceCategory().getId(), previous);
                }
            }
        }

        // A closed scheme with no rules at all in force refuses EVERY category,
        // which looks identical to "correctly refused" from the outside — an
        // empty dropdown and no error. It is almost always a configuration gap:
        // a scheme period assigned from a date earlier than its rules cover.
        // Said out loud here so the next occurrence is one log line, not an
        // investigation.
        if (bySourceCategory.isEmpty() && !Boolean.TRUE.equals(scheme.getAllowUnmappedCategories())) {
            log.warn("Compensation scheme {} refuses every category on {} for employee {}:"
                            + " it does not allow unmapped categories and has no rules in force for that date."
                            + " Check work_code_category_scheme_rules.valid_from against the employee's scheme period.",
                    scheme.getCode(), workDate, employeeId);
        }

        return new ResolutionContext(employeeId, workDate, scheme, bySourceCategory);
    }

    /**
     * The source categories this employee may actually select on this date, in
     * display order.
     *
     * <p>Returns SOURCE categories — the employee still records which shift they
     * really worked — each carrying the effective category and coefficient it
     * resolves to. It deliberately does not return the common effective category
     * as a selectable item: that category is a calculation target, not something
     * anyone worked.
     *
     * <p>Inactive and archived categories, disallowed categories, and categories
     * whose rule is out of its validity window are all excluded.
     */
    @Transactional(readOnly = true)
    public List<WorkCategoryResolution> listAllowedCategories(Long employeeId, LocalDate workDate) {
        ResolutionContext context = contextFor(employeeId, workDate);

        List<WorkCodeCategory> candidates =
                workCodeCategoryRepository.findByArchivedAtIsNullOrderByDisplayOrderAscIdAsc()
                        .stream()
                        .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                        .filter(c -> isCategoryInForce(c, workDate))
                        .toList();

        List<WorkCategoryResolution> allowed = new ArrayList<>();
        for (WorkCodeCategory category : candidates) {
            WorkCategoryResolution resolution = context.resolveFor(category);
            if (resolution.allowed()) {
                allowed.add(resolution);
            }
        }
        return allowed;
    }

    /**
     * Throwing validation for a category a client submitted directly.
     *
     * <p>The API must revalidate: a category that appeared in a dropdown earlier
     * is not evidence that it is still valid for the employee and date now being
     * submitted, and nothing stops a client from posting an id it never saw.
     */
    @Transactional(readOnly = true)
    public WorkCategoryResolution requireAllowed(Long employeeId, LocalDate workDate, Long sourceCategoryId) {
        return contextFor(employeeId, workDate).requireAllowed(sourceCategoryId);
    }

    // ------------------------------------------------------------------------

    private CompensationScheme resolveScheme(Long employeeId, LocalDate workDate) {
        List<EmployeeCompensationSchemeHistory> periods =
                schemeHistoryRepository.findActiveAt(employeeId, workDate);

        if (periods.isEmpty()) {
            throw new ConflictException(
                    "Zaposleni nema definisan način obračuna za datum " + workDate
                            + ". Dodajte period obračuna pre unosa rada.");
        }
        if (periods.size() > 1) {
            String ids = periods.stream()
                    .map(p -> String.valueOf(p.getId()))
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            log.error("Employee {} has {} overlapping compensation scheme periods on {} (ids: {})",
                    employeeId, periods.size(), workDate, ids);
            throw new ConflictException(
                    "Zaposleni ima preklapajuće periode obračuna za datum " + workDate
                            + ". Ispravite istoriju obračuna pre nastavka.");
        }

        CompensationScheme scheme = periods.getFirst().getCompensationScheme();
        if (!scheme.isUsable()) {
            throw new ConflictException(
                    "Način obračuna \"" + scheme.getCode() + "\" nije aktivan i ne može se koristiti za datum "
                            + workDate + ".");
        }
        return scheme;
    }

    /**
     * work_code_categories carries its own validity window. A category outside it
     * is not offerable even when a scheme rule would allow it.
     */
    private static boolean isCategoryInForce(WorkCodeCategory category, LocalDate date) {
        LocalDate from = category.getValidFrom();
        LocalDate until = category.getValidUntil();
        return (from == null || !date.isBefore(from))
                && (until == null || !date.isAfter(until));
    }

    /**
     * The existing normal coefficient logic, unchanged: the category's own
     * multiplier. {@code BigDecimal.valueOf(double)} goes through
     * {@code Double.toString}, so 1.2 becomes exactly {@code 1.2} rather than the
     * binary expansion {@code new BigDecimal(1.2)} would produce.
     */
    private static BigDecimal normalCoefficientOf(WorkCodeCategory category) {
        if (category == null || category.getNormMultiplier() == null) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(category.getNormMultiplier());
    }

    // ------------------------------------------------------------------------

    /**
     * One employee, one date, scheme and rules already loaded. Resolving a
     * category against this is pure computation — no further queries.
     */
    public final class ResolutionContext {

        private final Long employeeId;
        private final LocalDate workDate;
        private final CompensationScheme scheme;
        private final Map<Long, WorkCodeCategorySchemeRule> rulesBySourceCategory;

        private ResolutionContext(Long employeeId,
                                  LocalDate workDate,
                                  CompensationScheme scheme,
                                  Map<Long, WorkCodeCategorySchemeRule> rulesBySourceCategory) {
            this.employeeId = employeeId;
            this.workDate = workDate;
            this.scheme = scheme;
            this.rulesBySourceCategory = rulesBySourceCategory;
        }

        public CompensationScheme scheme() {
            return scheme;
        }

        public LocalDate workDate() {
            return workDate;
        }

        /** Resolve by id, loading the category if the caller does not have it. */
        public WorkCategoryResolution resolve(Long sourceCategoryId) {
            Objects.requireNonNull(sourceCategoryId, "sourceCategoryId");
            WorkCodeCategory category = workCodeCategoryRepository.findById(sourceCategoryId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Kategorija rada ne postoji: " + sourceCategoryId));
            return resolveFor(category);
        }

        /** Resolve an already-loaded category — the hot path in the recalc engine. */
        public WorkCategoryResolution resolveFor(WorkCodeCategory sourceCategory) {
            Objects.requireNonNull(sourceCategory, "sourceCategory");

            WorkCodeCategorySchemeRule rule = rulesBySourceCategory.get(sourceCategory.getId());

            if (rule == null) {
                boolean open = Boolean.TRUE.equals(scheme.getAllowUnmappedCategories());
                return build(
                        sourceCategory,
                        open ? sourceCategory : null,
                        open,
                        open ? normalCoefficientOf(sourceCategory) : null,
                        false,
                        null,
                        open ? WorkCategoryResolution.Reason.SCHEME_DEFAULT_ALLOWS
                             : WorkCategoryResolution.Reason.NO_RULE_AND_SCHEME_CLOSED);
            }

            if (!Boolean.TRUE.equals(rule.getIsAllowed())) {
                return build(sourceCategory, null, false, null, false, rule,
                        WorkCategoryResolution.Reason.EXPLICIT_RULE_DENIES);
            }

            // A null effective category on the rule means "no remap": the effective
            // category IS the source category. The source category is preserved
            // either way — it is never overwritten by the effective one.
            WorkCodeCategory effective = rule.getEffectiveCategory() != null
                    ? rule.getEffectiveCategory()
                    : sourceCategory;

            // Coefficient precedence: the rule's override wins; otherwise the
            // existing normal logic applies untouched.
            BigDecimal override = rule.getCoefficientOverride();
            boolean overridden = override != null;
            BigDecimal coefficient = overridden ? override : normalCoefficientOf(sourceCategory);

            return build(sourceCategory, effective, true, coefficient, overridden, rule,
                    WorkCategoryResolution.Reason.EXPLICIT_RULE);
        }

        /** As {@link #resolve} but throws a clear business error when disallowed. */
        public WorkCategoryResolution requireAllowed(Long sourceCategoryId) {
            WorkCodeCategory category = workCodeCategoryRepository.findById(sourceCategoryId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Kategorija rada ne postoji: " + sourceCategoryId));

            if (category.getArchivedAt() != null) {
                throw new IllegalArgumentException(
                        "Kategorija rada \"" + category.getCategoryNo() + "\" je arhivirana i ne može se koristiti.");
            }
            if (!Boolean.TRUE.equals(category.getIsActive())) {
                throw new IllegalArgumentException(
                        "Kategorija rada \"" + category.getCategoryNo() + "\" nije aktivna.");
            }
            if (!isCategoryInForce(category, workDate)) {
                throw new IllegalArgumentException(
                        "Kategorija rada \"" + category.getCategoryNo() + "\" ne važi za datum " + workDate + ".");
            }

            WorkCategoryResolution resolution = resolveFor(category);
            if (!resolution.allowed()) {
                log.info("Rejected work-code category {} for employee {} on {}: scheme={} reason={}",
                        category.getCategoryNo(), employeeId, workDate,
                        scheme.getCode(), resolution.resolutionReason());
                throw new IllegalArgumentException(
                        "Kategorija rada \"" + category.getCategoryNo()
                                + "\" nije dozvoljena za način obračuna \"" + scheme.getName()
                                + "\" na datum " + workDate + ".");
            }
            return resolution;
        }

        private WorkCategoryResolution build(WorkCodeCategory source,
                                             WorkCodeCategory effective,
                                             boolean allowed,
                                             BigDecimal coefficient,
                                             boolean coefficientOverridden,
                                             WorkCodeCategorySchemeRule rule,
                                             WorkCategoryResolution.Reason reason) {
            return new WorkCategoryResolution(
                    employeeId,
                    workDate,
                    scheme.getId(),
                    scheme.getCode(),
                    source.getId(),
                    source.getCategoryNo(),
                    effective == null ? null : effective.getId(),
                    effective == null ? null : effective.getCategoryNo(),
                    allowed,
                    coefficient,
                    coefficientOverridden,
                    rule == null ? null : rule.getId(),
                    rule == null ? null : rule.getValidFrom(),
                    rule == null ? null : rule.getValidUntil(),
                    reason);
        }
    }

    /** Comparator used when presenting resolutions in the same order as categories. */
    public static final Comparator<WorkCategoryResolution> BY_SOURCE_CODE =
            Comparator.comparing(WorkCategoryResolution::sourceCategoryCode,
                    Comparator.nullsLast(String::compareToIgnoreCase));
}
