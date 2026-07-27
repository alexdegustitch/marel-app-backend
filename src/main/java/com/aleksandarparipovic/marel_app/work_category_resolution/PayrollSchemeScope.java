package com.aleksandarparipovic.marel_app.work_category_resolution;

import java.util.Set;

/**
 * What one employee's compensation scheme allows across one payroll period.
 *
 * <p>A payroll month is a RANGE, and an employee can move between schemes inside
 * it. Everything here is therefore the **union over every period that overlaps
 * the month**, not the answer for a single date:
 *
 * <ul>
 *   <li>a work category allowed under any part of the month is included — if it
 *       were excluded, minutes already recorded against it would have no payroll
 *       row to land in and would silently vanish from the payslip;</li>
 *   <li>an adjustment line allowed under any part of the month is included;</li>
 *   <li>{@link #allowsPerformanceBonus()} is true if any part of the month
 *       allows it.</li>
 * </ul>
 *
 * <p>Union rather than intersection throughout, because the failure mode of
 * being too generous is a visible zero row, and the failure mode of being too
 * strict is money quietly disappearing.
 *
 * <p>The seeded cutover falls on a month boundary, so in practice one scheme
 * governs a whole month and these unions are exact.
 *
 * @param allowedWorkCategoryIds       work categories that may appear on the payslip
 * @param allowedAdjustmentCategoryIds adjustment lines that may appear on it
 * @param allowsPerformanceBonus       whether category bonus amounts are paid
 */
public record PayrollSchemeScope(
        Set<Long> allowedWorkCategoryIds,
        Set<Long> allowedAdjustmentCategoryIds,
        boolean allowsPerformanceBonus
) {
    public boolean allowsWorkCategory(Long categoryId) {
        return categoryId != null && allowedWorkCategoryIds.contains(categoryId);
    }

    public boolean allowsAdjustmentCategory(Long categoryId) {
        return categoryId != null && allowedAdjustmentCategoryIds.contains(categoryId);
    }
}
