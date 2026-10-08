package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_adjustment_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollAdjustmentCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable system code, e.g. MEAL_ALLOWANCE */
    @Column(name = "code", nullable = false, unique = true)
    private String code;

    /** Display name, e.g. Topli obrok */
    @Column(name = "name")
    private String name;

    /**
     * The section this category's lines sit in — and, through its direction,
     * what they do to the totals. Since V63 this is the one source the code
     * reads; the three legacy columns below stay only until the contract
     * migration retires them.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    private PayrollAdjustmentSection section;

    /** @deprecated superseded by {@link #section}; unread since V63, dropped by the contract migration. */
    @Deprecated
    @Column(name = "section_code")
    private String sectionCode;

    /** @deprecated superseded by {@link #section}; unread since V63, dropped by the contract migration. */
    @Deprecated
    @Column(name = "section_order")
    private Integer sectionOrder;

    /** Order inside the section */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /**
     * @deprecated no calculation reads this since V63 — {@code PayrollTotals}
     * computes with the section's {@code direction}. Still SERVED on DTOs
     * (šifarnik display, frozen documents) until the contract migration.
     */
    @Deprecated
    @Column(name = "impact_code")
    private String impactCode;

    /** AMOUNT or QTY_X_RATE */
    @Column(name = "input_type")
    private String inputType;

    @Column(name = "is_manual", nullable = false)
    private Boolean isManual = false;

    @Column(name = "allow_override", nullable = false)
    private Boolean allowOverride = false;

    /** What the user may override: AMOUNT, UNIT_AMOUNT, COMPONENTS */
    @Column(name = "override_target")
    private String overrideTarget;

    @Column(name = "allow_negative", nullable = false)
    private Boolean allowNegative = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "visible_in_ui", nullable = false)
    private Boolean visibleInUi = true;

    @Column(name = "visible_in_pdf", nullable = false)
    private Boolean visibleInPdf = true;

    @Column(name = "show_name", nullable = false)
    private Boolean showName = true;

    /** Backend calculator key for system-calculated lines, e.g. MEAL_BY_ELIGIBLE_SHIFTS */
    @Column(name = "calculation_key")
    private String calculationKey;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    // ── Phase 3: edit policy ─────────────────────────────────────────────────

    /**
     * The ONE input a user may change; the formula still runs afterwards.
     * NONE, AMOUNT, UNIT_AMOUNT, QUANTITY or CORRECTION.
     *
     * <p>Distinct from {@link #allowTotalOverride}, which bypasses the formula.
     * Collapsing the two is what made {@code allowOverride} unable to express
     * "the count is the system's, the price is yours" — the meal allowance.
     */
    @Column(name = "editable_input", nullable = false)
    private String editableInput = "NONE";

    /** Whether the final amount may be typed in directly, bypassing the formula. */
    @Column(name = "allow_total_override", nullable = false)
    private Boolean allowTotalOverride = false;

    /** Show the line even at 0 — how "commercial sees a zero bonus" is expressed. */
    @Column(name = "show_when_zero", nullable = false)
    private Boolean showWhenZero = true;

    /** A manual line that must be filled in before the item can be locked. */
    @Column(name = "required_manual_input", nullable = false)
    private Boolean requiredManualInput = false;

    /**
     * Whether payroll initialisation creates this category's row on every item.
     * FALSE marks a CARRIER for hand-added lines (the CUSTOM category): usable
     * through the custom-line door, never printed as an empty system row.
     */
    @Column(name = "seed_on_init", nullable = false)
    private Boolean seedOnInit = true;
}
