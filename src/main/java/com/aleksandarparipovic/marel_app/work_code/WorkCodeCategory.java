package com.aleksandarparipovic.marel_app.work_code;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "work_code_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkCodeCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_no", nullable = false)
    private String categoryNo;

    @Column(name = "category_name", nullable = false)
    private String categoryName;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "is_paid")
    private Boolean isPaid;

    @Column(name = "norm_multiplier", nullable = false)
    private Double normMultiplier = 1.0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private java.math.BigDecimal hourlyRate;

    @Column(name = "fixed_hourly_rate", nullable = false)
    private Boolean fixedHourlyRate = false;

    @Column(name = "affects_meal_allowance", nullable = false)
    private Boolean affectsMealAllowance = false;

    /**
     * Whether these hours count towards the daily and monthly efficiency
     * percentage — the administrator's declaration, editable in the šifarnik.
     *
     * <p>Nothing in the calculation reads it yet: the recalc derives its own
     * "affects norm" from {@code norm_multiplier > 0}
     * (see PayrollRunItemService). Wiring this flag into that derivation is the
     * "additional logic" the šifarnik form tells the administrator to raise
     * with the developer.
     */
    @Column(name = "affects_norm", nullable = false)
    @Builder.Default
    private Boolean affectsNorm = true;

    /**
     * Historically dead (see {@link #affectsWeekendBonus}'s javadoc) and still
     * unread; mapped now only so the šifarnik can keep it equal to
     * {@link #affectsMonthlyBonus}, which is the value the owner considers it
     * to mean.
     */
    @Column(name = "affects_bonus", nullable = false)
    @Builder.Default
    private Boolean affectsBonus = true;

    /**
     * Do these minutes count towards the 180 a day the weekend bonus needs.
     *
     * <p>Separate from {@link #affectsMonthlyBonus} because the two bonuses ask
     * different questions of different numbers: the weekend one asks whether
     * EVERY day of the week reached its minutes, the monthly one how many hours
     * the month came to. A category can reasonably count for one and not the
     * other, and until now neither could be said — both were decided in Java by
     * {@code type = 'WORK'}.
     *
     * <p>Neither is {@code affects_bonus}, which is a third thing again and dead:
     * not mapped here, never read, and not even the source of the snapshot that
     * shares its name on payroll rows — that one is derived from the type.
     */
    @Column(name = "affects_weekend_bonus", nullable = false)
    @Builder.Default
    private Boolean affectsWeekendBonus = true;

    /** Do these minutes count towards the hours the monthly bonus is measured on. */
    @Column(name = "affects_monthly_bonus", nullable = false)
    @Builder.Default
    private Boolean affectsMonthlyBonus = true;

    /**
     * The swatch this category is drawn with on the karton's shift timeline —
     * a CSS hex ('#3b82f6' or '#3b82f6cc'), or null for "not chosen", in which
     * case the client falls back to a stable colour derived from the code.
     *
     * <p>Cosmetic like {@link #categoryName} and {@link #note}: changing it does
     * NOT re-version the category (see V52 and WorkCodeCategoryAdminService).
     * Nothing in the calculation reads it.
     */
    @Column(name = "color", length = 9)
    private String color;

    /** How the timeline bar is filled: NONE | CHECKER | STRIPES. Cosmetic. */
    @Column(name = "pattern", nullable = false, length = 16)
    @Builder.Default
    private String pattern = "NONE";

    @Column(name = "display_order")
    private Integer displayOrder = 0;

    @Column(name = "base_category")
    private Boolean baseCategory;

    /**
     * Whether this category may be an employee's DEFAULT work category.
     *
     * <p>Only a presentation/assignment rule: it decides what the employee form
     * offers, and nothing in the calculation reads it. A category that is false
     * here is still worked and still reaches payroll — it simply is not a
     * standing assignment. See 2026-09-21-01.
     */
    // @Builder.Default, not just a field initialiser: the builder ignores the
    // initialiser and would insert NULL into a NOT NULL column — which is
    // exactly what 233 integration tests hit.
    @Builder.Default
    @Column(name = "is_basic_work_operation", nullable = false)
    private Boolean basicWorkOperation = true;

    @Column(name = "allows_parallel_work", nullable = false)
    private Boolean allowsParallelWork = false;

    /**
     * STANDARD | INJURY | EXTENDED — the role this SICK_LEAVE category plays in
     * the thirty-day rule, or null when it plays none. Declared data rather than
     * a pattern-match on codes or names: the rule must survive a renamed
     * category. See V39.
     */
    @Column(name = "sick_leave_kind")
    private String sickLeaveKind;

    /**
     * TRUE when this category always means a whole shift nobody worked (GO,
     * bolovanje, NO, ND). Such a day is drawn on the shift as one full-shift
     * log, dropped from the recalc aggregation (its minutes come through the
     * absence record), and shown on the calendar without times. See V40.
     */
    @Column(name = "is_full_day", nullable = false)
    @Builder.Default
    private Boolean isFullDay = false;

    /**
     * Whether this VERSION of the category governs any day of {@code [from, to]}.
     *
     * <p>A category is re-versioned over time (same {@code category_no}, new
     * row, adjacent validity windows), so "the categories" of a period are the
     * versions overlapping it — not every non-archived row. Both bounds
     * inclusive, matching the repository's date-window queries.
     */
    public boolean isInForceDuring(LocalDate from, LocalDate to) {
        return (validFrom == null || !validFrom.isAfter(to))
                && (validUntil == null || !validUntil.isBefore(from));
    }

    public boolean isInForceOn(LocalDate date) {
        return isInForceDuring(date, date);
    }

    // DB managed timestamps
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}