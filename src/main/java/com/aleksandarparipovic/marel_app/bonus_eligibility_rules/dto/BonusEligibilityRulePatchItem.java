package com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class BonusEligibilityRulePatchItem {

    @NotNull
    private Long id;

    private Integer minNumHours;

    private Integer saturdayCount;

    private BigDecimal bonusValue;

    private String note;

    private Boolean isActive;
}

