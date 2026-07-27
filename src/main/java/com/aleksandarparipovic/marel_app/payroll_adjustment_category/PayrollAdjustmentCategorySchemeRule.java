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
