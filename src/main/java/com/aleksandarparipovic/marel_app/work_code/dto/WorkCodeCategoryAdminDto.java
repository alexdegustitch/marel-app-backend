package com.aleksandarparipovic.marel_app.work_code.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One VERSION of a work-code category, as the šifarnik administration screen
 * sees it — every column the edit form writes, plus what the row's mappings say
 * about it.
 *
 * @param hasWeekendBonusPair   an OPEN {@code WEEKEND_BONUS} mapping hangs off
 *                              this version — the "B" pair stands.
 * @param hasNightShiftBonusPair the same for {@code NIGHT_SHIFT_BONUS} and the
 *                              "3" pair.
 * @param derivedFromCategoryId when this row IS such a pair: the base version it
 *                              mirrors. A derived row is edited through its
 *                              base, never directly.
 * @param current               whether this version governs today.
 * @param editable              whether the edit form may open this row: the
 *                              open-ended version of a chain that is not a
 *                              derived pair.
 */
public record WorkCodeCategoryAdminDto(
        Long id,
        String no,
        String name,
        String nameEn,
        String type,
        Double normMultiplier,
        LocalDate validFrom,
        LocalDate validUntil,
        Boolean isPaid,
        Boolean affectsNorm,
        String note,
        BigDecimal hourlyRate,
        Boolean fixedHourlyRate,
        Boolean affectsMealAllowance,
        Boolean basicWorkOperation,
        Boolean affectsWeekendBonus,
        Boolean affectsMonthlyBonus,
        Boolean isFullDay,
        String color,
        String pattern,
        Integer displayOrder,
        boolean hasWeekendBonusPair,
        boolean hasNightShiftBonusPair,
        Long derivedFromCategoryId,
        String derivedFromCategoryNo,
        boolean current,
        boolean editable
) {
}
