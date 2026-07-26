package com.aleksandarparipovic.marel_app.bonus_eligibility_rules.dto;

import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRule;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
public class BonusEligibilityRuleResponse {

    private final Long id;
    private final LocalDate period;
    private final Integer minNumHours;
    private final Integer saturdayCount;
    private final BigDecimal bonusValue;
    private final String note;
    private final Boolean isActive;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    public BonusEligibilityRuleResponse(BonusEligibilityRule entity) {
        this.id = entity.getId();
        this.period = entity.getPeriod();
        this.minNumHours = entity.getMinNumHours();
        this.saturdayCount = entity.getSaturdayCount();
        this.bonusValue = entity.getBonusValue();
        this.note = entity.getNote();
        this.isActive = entity.getIsActive();
        this.createdAt = entity.getCreatedAt();
        this.updatedAt = entity.getUpdatedAt();
        this.archivedAt = entity.getArchivedAt();
    }
}

