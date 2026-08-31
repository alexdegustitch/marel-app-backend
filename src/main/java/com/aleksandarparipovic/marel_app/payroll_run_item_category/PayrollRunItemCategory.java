package com.aleksandarparipovic.marel_app.payroll_run_item_category;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_run_item_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollRunItemCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_item_id", nullable = false)
    private PayrollRunItem payrollRunItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_code_category_id", nullable = false)
    private WorkCodeCategory workCodeCategory;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "total_minutes", nullable = false)
    private Integer totalMinutes;

    @Column(name = "total_paid_minutes", nullable = false)
    private Integer totalPaidMinutes;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "total_scrap", nullable = false)
    private Integer totalScrap;

    @Column(name = "weighted_norm_minutes", nullable = false)
    private BigDecimal weightedNormMinutes;

    @Column(name = "performance_coefficient")
    private BigDecimal performanceCoefficient;

    @Column(name = "category_coefficient_snapshot", nullable = false)
    private BigDecimal categoryCoefficientSnapshot;

    /**
     * The category's own coefficient, beside the one this row was priced at.
     *
     * <p>Nullable only for rows written before the column existed. The payslip
     * folds a category's rows into one line at THIS coefficient and scales the
     * hours so the amount is unchanged — a worker reading it sees their category
     * once, at the number they know it by.
     */
    @Column(name = "category_default_coefficient_snapshot")
    private BigDecimal categoryDefaultCoefficientSnapshot;

    @Column(name = "effective_minutes", nullable = false)
    private BigDecimal effectiveMinutes;

    @Column(name = "hourly_rate", nullable = false)
    private BigDecimal hourlyRate;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "category_is_paid_snapshot")
    private Boolean categoryIsPaidSnapshot;

    @Column(name = "category_affects_norm_snapshot")
    private Boolean categoryAffectsNormSnapshot;

    @Column(name = "category_affects_bonus_snapshot")
    private Boolean categoryAffectsBonusSnapshot;

    @Column(name = "bonus_amount")
    private BigDecimal bonusAmount;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}
