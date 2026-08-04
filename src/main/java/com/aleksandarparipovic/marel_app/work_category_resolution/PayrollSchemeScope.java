package com.aleksandarparipovic.marel_app.work_category_resolution;

import java.util.Map;
import java.util.Set;

/**
 * What one employee's compensation scheme allows across one payroll period.
 *
 * <p><b>One scheme, fully resolved — no union.</b> A scheme change now takes
 * effect on the first day of the following month (D1), so an employee has exactly
 * one scheme in any payroll month and there is nothing to merge. Two schemes in a
 * month is a configuration error, not a case to average over.
 *
 * <p>This replaces the old union-across-the-month behaviour. That union existed to
 * stop a mid-month change from making recorded work vanish from the payslip, and
 * it was the right answer while mid-month changes were possible. It also meant a
 * restricted employee could inherit a permission from the scheme they left, which
 * is not something anyone would configure on purpose. Forbidding the mid-month
 * change removes the need for the union and the leak with it.
 *
 * @param allowedWorkCategoryIds work categories that may appear on the payslip
 * @param componentsByCategoryId every adjustment line's fully resolved
 *                               configuration, keyed by category id. A category
 *                               missing from this map has no rule, which is an
 *                               incomplete configuration rather than a default
 * @param allowsPerformanceBonus whether category bonus amounts are paid
 */
public record PayrollSchemeScope(
        Long compensationSchemeId,
        String compensationSchemeCode,
        Set<Long> allowedWorkCategoryIds,
        Map<Long, EffectiveComponentConfig> componentsByCategoryId,
        boolean allowsPerformanceBonus
) {
    public boolean allowsWorkCategory(Long categoryId) {
        return categoryId != null && allowedWorkCategoryIds.contains(categoryId);
    }

    /**
     * Whether an adjustment line exists for this employee.
     *
     * <p>A category with no rule answers {@code false}. It is not silently allowed:
     * {@link PayrollSchemeScopeService} completes the map for every active category
     * and refuses to build a scope with a gap in it, so reaching this method with
     * an unknown id means the category was created after the scope was resolved.
     */
    public boolean allowsAdjustmentCategory(Long categoryId) {
        EffectiveComponentConfig config = componentsByCategoryId.get(categoryId);
        return config != null && config.allowed();
    }

    /** The resolved configuration for one line, or {@code null} if it has no rule. */
    public EffectiveComponentConfig componentConfig(Long categoryId) {
        return categoryId == null ? null : componentsByCategoryId.get(categoryId);
    }
}
