package com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto;

import java.util.List;

public record BonusMinHoursRulesByYearDto(
        Integer year,
        List<BonusMinHoursRuleResponse> rules
) {}

