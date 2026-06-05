package com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BonusEligibilityRuleBulkUpdateRequest {

    @NotNull
    private Integer year;

    @NotNull
    private Integer month;

    @NotEmpty
    @Valid
    private List<BonusEligibilityRulePatchItem> rules;
}

