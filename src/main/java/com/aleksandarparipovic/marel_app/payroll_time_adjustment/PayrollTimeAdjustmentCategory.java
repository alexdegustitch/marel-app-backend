package com.aleksandarparipovic.marel_app.payroll_time_adjustment;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * The kinds of time correction that exist.
 *
 * <p>Deliberately separate from {@code payroll_adjustment_categories}: every
 * impact code there moves money into a total, and minutes are not money. See the
 * header of 2026-08-27-01 for the full reasoning.
 */
@Entity
@Table(name = "payroll_time_adjustment_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollTimeAdjustmentCategory {

    /** The only impact today: minutes an employee is paid for. */
    public static final String IMPACT_PAYABLE_MINUTES = "PAYABLE_MINUTES";
    /** A correction a person enters; the calculator leaves system_minutes at 0. */
    public static final String KEY_MANUAL = "MANUAL";
    /** Seeded, and what manual_adjusted_minutes has always meant. */
    public static final String CODE_MANUAL_CORRECTION = "MANUAL_CORRECTION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Builder.Default
    @Column(name = "impact_code", nullable = false)
    private String impactCode = IMPACT_PAYABLE_MINUTES;

    @Builder.Default
    @Column(name = "calculation_key", nullable = false)
    private String calculationKey = KEY_MANUAL;

    @Builder.Default
    @Column(name = "allow_negative", nullable = false)
    private Boolean allowNegative = true;

    @Builder.Default
    @Column(name = "allow_positive", nullable = false)
    private Boolean allowPositive = true;

    /** Enforced in the database too, against this flag rather than unconditionally. */
    @Builder.Default
    @Column(name = "require_reason", nullable = false)
    private Boolean requireReason = true;

    @Builder.Default
    @Column(name = "visible_in_ui", nullable = false)
    private Boolean visibleInUi = true;

    @Builder.Default
    @Column(name = "visible_in_pdf", nullable = false)
    private Boolean visibleInPdf = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /** Usable means active and not archived — retired categories keep their rows. */
    public boolean isUsable() {
        return Boolean.TRUE.equals(isActive) && archivedAt == null;
    }
}
