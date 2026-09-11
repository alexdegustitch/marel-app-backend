package com.aleksandarparipovic.marel_app.work_code.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the šifarnik form submits, for create and edit alike.
 *
 * <p>{@code validFrom} is the date the submitted VALUES take effect. On create
 * it opens the first version; on edit it decides the path — equal to the
 * current version's start it corrects that version in place, later than it it
 * closes the current version and opens a new one, so a recalculated old month
 * keeps the values it was worked under.
 *
 * @param weekendBonusPair  maintain the derived "B" category and its
 *                          {@code WEEKEND_BONUS} mapping.
 * @param nightShiftBonusPair maintain the derived "3" category and its
 *                          {@code NIGHT_SHIFT_BONUS} mapping.
 * @param schemeRules       step 2 — one entry per compensation scheme. An entry
 *                          matching the scheme's defaults writes no rule row
 *                          for an open scheme; a closed scheme always gets its
 *                          decision written, so the data can tell a decision
 *                          from an oversight.
 */
public record UpsertWorkCodeCategoryRequest(
        String categoryNo,
        String categoryName,
        String type,
        Double normMultiplier,
        LocalDate validFrom,
        Boolean isPaid,
        Boolean affectsNorm,
        String note,
        Boolean fixedHourlyRate,
        BigDecimal hourlyRate,
        Boolean affectsMealAllowance,
        Boolean baseOperation,
        Boolean affectsWeekendBonus,
        Boolean affectsMonthlyBonus,
        Boolean isFullDay,
        Boolean weekendBonusPair,
        Boolean nightShiftBonusPair,
        List<SchemeRuleInput> schemeRules
) {

    /**
     * @param effectiveCategoryId {@code null} or the category's own id = no
     *                            remap; anything else is the category the base
     *                            calculation lands on.
     */
    public record SchemeRuleInput(
            Long schemeId,
            Long effectiveCategoryId,
            BigDecimal coefficientOverride,
            Boolean isAllowed,
            Boolean isSelectable,
            String note
    ) {
    }
}
