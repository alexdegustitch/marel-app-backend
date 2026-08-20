package com.aleksandarparipovic.marel_app.bonus_min_hours_rules;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One interval during which a month's minimum hours held a particular pair of values.
 *
 * <p>Append-and-close: a change closes the open row ({@code validUntil = now}) and opens a new
 * one. The database enforces that at most one row per month is open, so "what was in force on
 * the 15th" is a plain range query and can never have two answers.
 *
 * <p>Both numbers are stored, not just the effective one. A row that says only "176" cannot
 * later explain whether that was the calendar's answer or somebody's decision — which is the
 * question this history exists to answer.
 */
@Entity
@Table(name = "bonus_min_hours_rule_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BonusMinHoursRuleHistory {

    /** What put this row here. */
    public enum Source {
        /** The work calendar recomputed the month. */
        CALENDAR_SYNC,
        /** Somebody set the minimum by hand. */
        MANUAL_SET,
        /** Somebody dropped the manual value, returning the month to the calendar. */
        MANUAL_RESET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period", nullable = false)
    private LocalDate period;

    @Column(name = "system_min_num_hours", nullable = false)
    private Integer systemMinNumHours;

    @Column(name = "manual_min_num_hours")
    private Integer manualMinNumHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    /** Null while this is the row in force. */
    @Column(name = "valid_until")
    private OffsetDateTime validUntil;

    /** Null for a change the calendar made on its own. */
    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "note")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** The number that applied while this row was in force. */
    @Transient
    public Integer getEffectiveMinNumHours() {
        return manualMinNumHours != null ? manualMinNumHours : systemMinNumHours;
    }
}
