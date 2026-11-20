package com.aleksandarparipovic.marel_app.bonus.dto;

import com.aleksandarparipovic.marel_app.bonus.BonusCategoryAmountPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One spell of a bonus category's amount, as the šifarnik's history reads it. */
public record BonusAmountPeriodDto(
        Long id,
        BigDecimal amount,
        LocalDate validFrom,
        LocalDate validTo,
        String note
) {
    public static BonusAmountPeriodDto from(BonusCategoryAmountPeriod p) {
        return new BonusAmountPeriodDto(
                p.getId(), p.getAmount(), p.getValidFrom(), p.getValidTo(), p.getNote());
    }
}
