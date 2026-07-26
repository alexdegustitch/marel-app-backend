package com.aleksandarparipovic.marel_app.bonus_eligibility_rules;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "bonus_eligibility_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BonusEligibilityRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period", nullable = false)
    private LocalDate period;

    @Column(name = "min_num_hours", nullable = false)
    private Integer minNumHours;

    @Column(name = "saturday_count")
    private Integer saturdayCount;

    @Column(name = "bonus_value", precision = 12, scale = 2)
    private BigDecimal bonusValue;

    @Column(name = "note")
    private String note;

    // Whether the calendar date for this Saturday ordinal is actually worked
    // (per WorkCalendarDayEffectiveStatus.isWorkingForBonusPurposes). false = the
    // row is shown grayed out — the Saturday isn't worked that month.
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}

