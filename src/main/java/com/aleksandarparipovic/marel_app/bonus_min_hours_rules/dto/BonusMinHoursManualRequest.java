package com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

/** Setting a month's minimum by hand. */
@Getter
public class BonusMinHoursManualRequest {

    @NotNull
    @Min(1)
    private Integer manualMinNumHours;

    /** Why, optionally — it lands in the history beside the number. */
    private String note;
}
