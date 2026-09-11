package com.aleksandarparipovic.marel_app.work_code.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One compensation scheme's step-2 state for one category, shaped for the
 * šifarnik form: the scheme itself, and either the in-force rule's values or
 * the scheme's defaults when no rule exists.
 *
 * @param allowUnmappedCategories the scheme's default, so the form can say what
 *                                "no rule" means there: an open scheme allows an
 *                                unruled category, a closed one refuses it.
 * @param hasRule                 whether an explicit rule row is in force.
 * @param effectiveCategoryId     {@code null} = the category maps to itself.
 * @param ruleValidFrom           the in-force rule's own start, when one exists.
 */
public record WorkCodeCategorySchemeRuleFormDto(
        Long schemeId,
        String schemeCode,
        String schemeName,
        Boolean allowUnmappedCategories,
        boolean hasRule,
        Long effectiveCategoryId,
        String effectiveCategoryNo,
        BigDecimal coefficientOverride,
        Boolean isAllowed,
        Boolean isSelectable,
        String note,
        LocalDate ruleValidFrom
) {
}
