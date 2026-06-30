package com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto;

import java.util.List;

public record BonusEligibilityRulesByYearDto(
        Integer year,
        List<BonusEligibilityRulesByMonthDto> months
) {}
