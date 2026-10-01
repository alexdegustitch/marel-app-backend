package com.aleksandarparipovic.marel_app.performance_mark.dto;

import com.aleksandarparipovic.marel_app.performance_mark.PerformanceMark;

import java.math.BigDecimal;

/**
 * One pickable mark for the payroll screen: the version of the ocena in force
 * on the asked date, with enough to render "3 — −45,00 RSD/h" and preview the
 * adjusted rate client-side.
 */
public record PerformanceMarkOptionDto(
        Long id,
        BigDecimal mark,
        BigDecimal amount,
        String amountUnit
) {
    public static PerformanceMarkOptionDto from(PerformanceMark p) {
        return new PerformanceMarkOptionDto(p.getId(), p.getMark(), p.getAmount(), p.getAmountUnit().name());
    }
}
