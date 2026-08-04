package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import com.aleksandarparipovic.marel_app.compensation_scheme.CompensationScheme;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Whether one payroll adjustment category is available under one compensation
 * scheme.
 *
 * <p><b>Absent means allowed.</b> That is the opposite default from
 * {@link com.aleksandarparipovic.marel_app.work_code_category_scheme_rules.WorkCodeCategorySchemeRule},
 * and deliberately so: for a work category "no rule" means "unknown
 * coefficient", which must be refused, whereas an adjustment category is a
 * labelled amount. Closed-by-default here would make every future adjustment
 * category silently disappear for restricted employees, and a missing payslip
 * line is far harder to spot than an extra one.
 *
 * <p>So in practice a row exists to say {@code false}.
 */
@Entity
@Table(name = "payroll_adjustment_category_scheme_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollAdjustmentCategorySchemeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compensation_scheme_id", nullable = false)
    private CompensationScheme compensationScheme;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_adjustment_category_id", nullable = false)
    private PayrollAdjustmentCategory payrollAdjustmentCategory;

    @Column(name = "is_allowed", nullable = false)
    @Builder.Default
    private Boolean isAllowed = true;

    // ── Phase 5: what the scheme says beyond "allowed or not" ────────────────
    //
    // Every nullable field below means "inherit from the category" when null. A
    // scheme states only what it CHANGES, which is what keeps a 39-row matrix
    // readable instead of 39 rows of duplicated defaults.

    /**
     * INHERIT, ZERO or MANUAL. NOT NULL with an explicit INHERIT rather than
     * nullable, because "this scheme does not calculate this line" and "this
     * scheme has no opinion" are different statements and both need saying.
     */
    @Column(name = "calculation_mode", nullable = false)
    @Builder.Default
    private String calculationMode = "INHERIT";

    @Column(name = "visible_in_ui")
    private Boolean visibleInUi;

    @Column(name = "visible_in_pdf")
    private Boolean visibleInPdf;

    @Column(name = "show_when_zero")
    private Boolean showWhenZero;

    @Column(name = "editable_input")
    private String editableInput;

    @Column(name = "allow_total_override")
    private Boolean allowTotalOverride;

    @Column(name = "required_manual_input")
    private Boolean requiredManualInput;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** Inclusive last day; {@code null} = open-ended. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at", insertable = false)
    private OffsetDateTime archivedAt;
}
