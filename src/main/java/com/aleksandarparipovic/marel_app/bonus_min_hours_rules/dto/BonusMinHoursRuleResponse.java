package com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto;

import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRule;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
public class BonusMinHoursRuleResponse {

    private final Long id;
    private final LocalDate period;
    /** What the work calendar computed for the month. */
    private final Integer minNumHours;
    /** What somebody set by hand, or null when nobody has. */
    private final Integer manualMinNumHours;
    /** The one to apply, and the one the screen shows plainly. */
    private final Integer effectiveMinNumHours;
    private final OffsetDateTime manualSetAt;
    private final Long manualSetBy;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;

    public BonusMinHoursRuleResponse(BonusMinHoursRule e) {
        this.id = e.getId();
        this.period = e.getPeriod();
        this.minNumHours = e.getMinNumHours();
        this.manualMinNumHours = e.getManualMinNumHours();
        this.effectiveMinNumHours = e.getEffectiveMinNumHours();
        this.manualSetAt = e.getManualSetAt();
        this.manualSetBy = e.getManualSetBy();
        this.createdAt = e.getCreatedAt();
        this.updatedAt = e.getUpdatedAt();
        this.archivedAt = e.getArchivedAt();
    }
}

