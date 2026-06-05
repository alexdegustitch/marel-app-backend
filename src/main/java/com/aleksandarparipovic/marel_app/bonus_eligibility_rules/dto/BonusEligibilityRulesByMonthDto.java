package com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto;

import java.util.List;

public record BonusEligibilityRulesByMonthDto(
        Integer month,
        List<BonusEligibilityRuleResponse> rules
) {}

