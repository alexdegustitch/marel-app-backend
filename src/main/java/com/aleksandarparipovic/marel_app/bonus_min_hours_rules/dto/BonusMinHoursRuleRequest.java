package com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.time.LocalDate;

@Getter
public class BonusMinHoursRuleRequest {

    @NotNull
    private LocalDate period;

    @NotNull
    @Min(1)
    private Integer minNumHours;
}

