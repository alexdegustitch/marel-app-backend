package com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto;

import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleHistory;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One interval of a month's minimum hours, as the screen reads it. */
@Getter
public class BonusMinHoursRuleHistoryDto {

    private final Long id;
    private final LocalDate period;
    private final Integer systemMinNumHours;
    private final Integer manualMinNumHours;
    /** What applied while this row was in force. */
    private final Integer effectiveMinNumHours;
    private final String source;
    private final OffsetDateTime validFrom;
    private final OffsetDateTime validUntil;
    private final Long changedBy;
    private final String note;

    public BonusMinHoursRuleHistoryDto(BonusMinHoursRuleHistory e) {
        this.id = e.getId();
        this.period = e.getPeriod();
        this.systemMinNumHours = e.getSystemMinNumHours();
        this.manualMinNumHours = e.getManualMinNumHours();
        this.effectiveMinNumHours = e.getEffectiveMinNumHours();
        this.source = e.getSource() == null ? null : e.getSource().name();
        this.validFrom = e.getValidFrom();
        this.validUntil = e.getValidUntil();
        this.changedBy = e.getChangedBy();
        this.note = e.getNote();
    }
}
