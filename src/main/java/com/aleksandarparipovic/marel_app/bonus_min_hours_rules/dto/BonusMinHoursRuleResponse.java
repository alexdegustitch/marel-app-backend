package com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto;

import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRule;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
public class BonusMinHoursRuleResponse {

    private final Long id;
    private final LocalDate period;
    private final Integer minNumHours;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    public BonusMinHoursRuleResponse(BonusMinHoursRule e) {
        this.id = e.getId();
        this.period = e.getPeriod();
        this.minNumHours = e.getMinNumHours();
        this.createdAt = e.getCreatedAt();
        this.updatedAt = e.getUpdatedAt();
        this.archivedAt = e.getArchivedAt();
    }
}

