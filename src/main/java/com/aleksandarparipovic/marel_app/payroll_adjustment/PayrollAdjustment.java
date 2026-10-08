package com.aleksandarparipovic.marel_app.payroll_adjustment;

import com.aleksandarparipovic.marel_app.payroll_adjustment_category.PayrollAdjustmentCategory;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Entity
// One row per (item, category) is enforced by a PARTIAL unique index over the
// non-custom rows (V59) — not expressible as a JPA @UniqueConstraint.
@Table(name = "payroll_adjustments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_item_id", nullable = false)
    private PayrollRunItem payrollRunItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_adjustment_category_id", nullable = false)
    private PayrollAdjustmentCategory payrollAdjustmentCategory;

    /** System-calculated quantity (e.g. number of eligible shifts for meal allowance) */
    @Column(name = "system_quantity")
    private BigDecimal systemQuantity;

    /** Currently active quantity — equals system_quantity unless overridden */
    @Column(name = "quantity")
    private BigDecimal quantity;

    /** System-calculated unit amount (e.g. meal allowance rate per shift) */
    @Column(name = "system_unit_amount")
    private BigDecimal systemUnitAmount;

    /** Currently active unit amount — equals system_unit_amount unless overridden */
    @Column(name = "unit_amount")
    private BigDecimal unitAmount;

    /** System-calculated total amount */
    @Column(name = "system_amount")
    private BigDecimal systemAmount;

    /** Currently active/final amount — equals system_amount unless overridden */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /** True when user has manually overridden the system value */
    @Column(name = "is_overridden", nullable = false)
    private Boolean isOverridden = false;

    @Column(name = "note")
    private String note;

    /**
     * The name THIS line shows, typed by whoever put it there. NULL means the
     * category's name, resolved with translations exactly as before. A typed
     * name is a verbatim label and prints the same in every document language.
     */
    @Column(name = "display_name")
    private String displayName;

    /**
     * TRUE for a line a person added by hand on one payroll — a second
     * "Isplaćeno" with its own name — as opposed to the row-per-category set the
     * initialisation creates. Never flipped after insert. Custom rows sit
     * outside the one-row-per-category rule and outside every per-category
     * calculator lookup; they are reached by id and summed by section.
     */
    @Column(name = "is_custom", nullable = false)
    @Builder.Default
    private Boolean isCustom = false;

    /**
     * Where THIS line sits inside its section on THIS payroll. NULL means the
     * category's own sort_order — the šifarnik is the default, the line is the
     * decision, and reordering one month never rearranges another. (The LIVE
     * order; sort_order_snapshot stays the number frozen at lock time.)
     */
    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "is_applied", nullable = false)
    private Boolean isApplied = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "edited_by")
    private User editedBy;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    // ── Phase 3: calculated result and edit state ────────────────────────────

    /**
     * A delta the user adds ON TOP of the system figure — the bonus correction.
     * Not an override: the system value stays visible and the two are summed.
     */
    /**
     * What the rules produced for the correction, beside what is effective.
     *
     * <p>The one system counterpart the line was missing. For the monthly bonus it
     * is the hours tier from bonus_eligibility_rules — without it, a tier the rules
     * paid cannot be told apart from one somebody typed, which is the whole reason
     * the override state could not move off payroll_run_items yet.
     */
    @Column(name = "system_correction_amount", nullable = false)
    // @Builder.Default, or builder() leaves it null and the NOT NULL fires on the
    // first insert — the same trap PayrollTimeAdjustment fell into.
    @Builder.Default
    private BigDecimal systemCorrectionAmount = BigDecimal.ZERO;

    @Column(name = "correction_amount", nullable = false)
    @Builder.Default
    private BigDecimal correctionAmount = BigDecimal.ZERO;

    /** Mandatory from phase 4c, when {@code isOverridden} narrows to a hard total override. */
    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    /** TRUE once a user has entered a value, including an explicit 0. */
    @Column(name = "has_manual_input", nullable = false)
    @Builder.Default
    private Boolean hasManualInput = false;

    /** CALCULATED, PENDING_INPUT, MANUAL, OVERRIDDEN or ERROR. */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "CALCULATED";

    /**
     * What the calculator was given, and why it produced what it did.
     *
     * <p>Carries the reason for a zero. An unexplained zero on a payslip cannot be
     * told apart from a bug, and somebody has to be able to answer "why did this
     * person get no transport this month".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation_inputs", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> calculationInputs = Map.of();

    @Column(name = "calculated_at")
    private OffsetDateTime calculatedAt;

    // ── Phase 4: display snapshot ────────────────────────────────────────────
    // Only what changes a NUMBER or a POSITION on the document. The NAME is
    // deliberately not snapshotted: it is resolved through
    // payroll_adjustment_category_translations, and freezing it would mean that
    // correcting a Serbian label required rewriting every historical row.

    @Column(name = "section_code_snapshot")
    private String sectionCodeSnapshot;

    @Column(name = "impact_code_snapshot")
    private String impactCodeSnapshot;

    @Column(name = "sort_order_snapshot")
    private Integer sortOrderSnapshot;

    @Column(name = "visible_in_ui_snapshot")
    private Boolean visibleInUiSnapshot;

    @Column(name = "visible_in_pdf_snapshot")
    private Boolean visibleInPdfSnapshot;

    @Column(name = "show_when_zero_snapshot")
    private Boolean showWhenZeroSnapshot;
}
