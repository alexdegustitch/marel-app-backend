package com.aleksandarparipovic.marel_app.payroll_time_adjustment;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One correction to an employee's payable minutes.
 *
 * <p>A row per correction rather than one integer on the item, so a month can
 * hold two corrections with different causes, each with its own reason and
 * author, and one can be undone without recomputing the other by hand.
 *
 * <p>There is no "zero correction": the database rejects it. Absence of a row is
 * how "nothing was corrected" is said, which is why this table needs no
 * equivalent of the show-when-zero problem the money side has.
 */
@Entity
@Table(name = "payroll_time_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollTimeAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_item_id", nullable = false)
    private PayrollRunItem payrollRunItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_time_adjustment_category_id", nullable = false)
    private PayrollTimeAdjustmentCategory category;

    /** What a calculator produced. 0 for a MANUAL category. */
    @Builder.Default
    @Column(name = "system_minutes", nullable = false)
    private Integer systemMinutes = 0;

    /** The effective correction. Signed; negative takes time away. Never 0. */
    @Column(name = "minutes", nullable = false)
    private Integer minutes;

    /** Separates "a person entered this" from "the system computed it". */
    @Builder.Default
    @Column(name = "has_manual_input", nullable = false)
    private Boolean hasManualInput = false;

    @Column(name = "reason")
    private String reason;

    @Column(name = "note")
    private String note;

    /** Excluded from the total without being deleted. */
    @Builder.Default
    @Column(name = "is_applied", nullable = false)
    private Boolean isApplied = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "edited_by")
    private Long editedBy;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /** Counts towards the payable total: applied and not archived. */
    public boolean countsTowardsTotal() {
        return Boolean.TRUE.equals(isApplied) && archivedAt == null;
    }
}
