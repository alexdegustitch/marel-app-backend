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
    @Column(name = "is_base_operation", nullable = false)
    private Boolean baseOperation = true;

    @Column(name = "allows_parallel_work", nullable = false)
    private Boolean allowsParallelWork = false;

    // DB managed timestamps
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}