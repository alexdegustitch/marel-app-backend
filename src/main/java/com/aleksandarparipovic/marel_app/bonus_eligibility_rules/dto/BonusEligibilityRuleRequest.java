package com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class BonusEligibilityRuleRequest {

    @NotNull
    private LocalDate period;

    @NotNull
    @Min(1)
    private Integer minNumHours;

    @Min(0)
    @Max(5)
    private Integer saturdayCount;

    @DecimalMin("0.00")
    private BigDecimal bonusValue;

    private String note;
}

