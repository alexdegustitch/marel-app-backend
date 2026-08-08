package com.aleksandarparipovic.marel_app.work_code.dto;

import java.math.BigDecimal;

/**
 * @param no          the stable category code. Never translated — it is an
 *                    identifier, not a label.
 * @param name        the default-locale name, held on the master row.
 * @param displayName the name in the requested locale, falling back to
 *                    {@code name}. Never null, so a missing translation renders
 *                    the Serbian name rather than a blank cell.
 * @param nameEn      the English translation, or null when none exists. Kept
 *                    separate from {@code displayName} so an administration
 *                    screen can distinguish "not translated yet" from
 *                    "translated", which {@code displayName} alone cannot say.
 */
public record WorkCodeCategoryDto(
        Long id,
        String no,
        String name,
        String displayName,
        String nameEn,
        Double normMultiplier,
        String note,
        BigDecimal hourlyRate,
        Boolean fixedHourlyRate,
        Boolean affectsMealAllowance,
        Integer displayOrder,
        Boolean baseCategory,
        /** May this be an employee's default work category. */
        Boolean baseOperation,
        Boolean allowsParallelWork
) {
}
