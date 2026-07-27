package com.aleksandarparipovic.marel_app.payroll_adjustment_category;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A translated display name for one {@link PayrollAdjustmentCategory}.
 *
 * <p>{@code payroll_adjustment_categories.name} remains the default and the
 * fallback; the {@code code} is never translated.
 *
 * <p>{@code payroll_adjustments} — the transactional rows — deliberately carry no
 * translated name. A payslip resolves each adjustment's display name through its
 * {@code payroll_adjustment_category_id}. Copying the name onto every adjustment
 * would duplicate master data across thousands of rows and guarantee they
 * diverge the first time someone corrects a typo.
 */
@Entity
@Table(name = "payroll_adjustment_category_translations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollAdjustmentCategoryTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_adjustment_category_id", nullable = false)
    private PayrollAdjustmentCategory payrollAdjustmentCategory;

    @Column(name = "locale", nullable = false, length = 35)
    private String locale;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;
}
