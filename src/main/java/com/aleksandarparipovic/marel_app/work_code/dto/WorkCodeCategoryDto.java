package com.aleksandarparipovic.marel_app.work_code.dto;

import java.math.BigDecimal;

public record WorkCodeCategoryDto(
        Long id,
        String no,
        String name,
        Double normMultiplier,
        String note,
        BigDecimal hourlyRate,
        Boolean fixedHourlyRate,
        Boolean affectsMealAllowance,
        Integer displayOrder,
        Boolean baseCategory
) {
}
