package com.aleksandarparipovic.marel_app.work_category_resolution;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The result of resolving one employee's compensation scheme against one source
 * work-code category on one work date.
 *
 * <p>Immutable and self-describing: everything a caller needs to write a
 * historical snapshot, build a report row, or explain a rejection is here, so no
 * caller ever has to re-derive part of it and risk disagreeing with this service.
 *
 * <p><strong>The three category concepts stay separate.</strong> This record
 * carries the first two; the third belongs to the mapping engine:
 * <ul>
 *   <li>{@code sourceCategory*} — what the employee actually worked. Preserved
 *       always, including when it is disallowed.</li>
 *   <li>{@code effectiveCategory*} — what the employee-specific base calculation
 *       uses. Equal to the source category unless a scheme rule remaps it.</li>
 *   <li>the derived/contextual category from {@code work_code_category_mappings}
 *       (night, weekend, parallel machines) — resolved separately, from the
 *       SOURCE category, and never replaced by the effective category.</li>
 * </ul>
 *
 * @param allowed              false means the calculation must not resolve to
 *                             this category for this employee on this date;
 *                             {@link #resolutionReason} says why, and the
 *                             coefficient is meaningless
 * @param selectable           false means a supervisor may not CHOOSE it when
 *                             entering work, even though the calculation may
 *                             still land on it. The common effective category is
 *                             exactly that: allowed, never offered
 * @param coefficient          the resolved coefficient, {@code null} only when
 *                             {@code allowed} is false
 * @param coefficientOverridden true when the coefficient came from the scheme
 *                             rule rather than from the category's own
 *                             {@code norm_multiplier}
 * @param schemeRuleId         the rule that produced this result, or {@code null}
 *                             when the scheme allowed the category by default
 */
public record WorkCategoryResolution(
        Long employeeId,
        LocalDate workDate,

        Long compensationSchemeId,
        String compensationSchemeCode,

        Long sourceCategoryId,
        String sourceCategoryCode,

        Long effectiveCategoryId,
        String effectiveCategoryCode,

        boolean allowed,
        boolean selectable,

        BigDecimal coefficient,
        boolean coefficientOverridden,

        Long schemeRuleId,
        LocalDate schemeRuleValidFrom,
        LocalDate schemeRuleValidUntil,

        Reason resolutionReason
) {

    /**
     * Why the resolution came out the way it did. Kept as an enum rather than a
     * free-text message so callers can branch on it and so the reason can be
     * logged and asserted in tests without matching on prose.
     */
    public enum Reason {
        /** An explicit, in-force scheme rule allowed the category. */
        EXPLICIT_RULE,
        /** No rule existed and the scheme allows unmapped categories. */
        SCHEME_DEFAULT_ALLOWS,
        /** No rule existed and the scheme does not allow unmapped categories. */
        NO_RULE_AND_SCHEME_CLOSED,
        /** An explicit rule exists and denies the category. */
        EXPLICIT_RULE_DENIES
    }

    /** True when the scheme remapped the base calculation to a different category. */
    public boolean isCategoryRemapped() {
        return effectiveCategoryId != null && !effectiveCategoryId.equals(sourceCategoryId);
    }
}
