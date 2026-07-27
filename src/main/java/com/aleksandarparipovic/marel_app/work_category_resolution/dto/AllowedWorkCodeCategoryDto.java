package com.aleksandarparipovic.marel_app.work_category_resolution.dto;

import java.math.BigDecimal;

/**
 * One source category an employee may select, with the effective category and
 * coefficient it resolves to.
 *
 * <p>The list returns SOURCE categories, not the common effective category: the
 * employee still records which shift they actually worked. The effective fields
 * are secondary information the work-entry form can show quietly ("Calculation
 * category: ..., Coefficient: ...") — they are not another selectable option.
 *
 * <p>{@code categoryName} is already localised for the requested locale, with
 * fallback to the default name. {@code categoryCode} is never translated.
 */
public record AllowedWorkCodeCategoryDto(
        Long categoryId,
        String categoryCode,
        String categoryName,

        Long effectiveCategoryId,
        String effectiveCategoryCode,
        String effectiveCategoryName,

        BigDecimal coefficient,
        boolean coefficientOverridden,

        String compensationSchemeCode
) {
}
