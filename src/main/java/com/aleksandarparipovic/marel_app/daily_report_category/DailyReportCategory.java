package com.aleksandarparipovic.marel_app.daily_report_category;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "daily_report_categories",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_daily_report_category_report_category",
                columnNames = {"daily_report_id", "work_code_category_id", "norm_multiplier"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyReportCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_report_id", nullable = false)
    private DailyReport dailyReport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_code_category_id", nullable = false)
    private WorkCodeCategory workCodeCategory;

    @Column(name = "total_minutes", nullable = false)
    private Integer totalMinutes;

    @Column(name = "total_paid_minutes", nullable = false)
    private Integer totalPaidMinutes;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "total_scrap", nullable = false)
    private Integer totalScrap;

    @Column(name = "total_weighted_norm_minutes", nullable = false)
    private BigDecimal totalWeightedNormMinutes;

    @Column(name = "performance_coefficient", nullable = false)
    private BigDecimal performanceCoefficient;

    @Column(name = "approved_performance_coefficient", nullable = false)
    private BigDecimal approvedPerformanceCoefficient;

    /**
     * The coefficient this row's work was calculated at.
     *
     * <p>Part of the unique key, because one category is no longer one row: an
     * operation whose coefficient a supervisor typed over sits beside the rest of
     * the same category at the resolved one, and a single number could not
     * represent both.
     *
     * <p>Read by the payroll instead of the category's CURRENT multiplier, so
     * recalculating an old month prices it the way it was recorded.
     */
    // Defaulted rather than left null: the column is NOT NULL with a database
    // DEFAULT, but Hibernate names every mapped column in the INSERT, so an unset
    // field arrives as an explicit NULL and the default never applies. One is the
    // neutral weight — a row nobody stated a coefficient for is a row that is not
    // weighted.
    @Builder.Default
    @Column(name = "norm_multiplier", nullable = false)
    private BigDecimal normMultiplier = BigDecimal.ONE;

    /**
     * What this row WOULD have been calculated at.
     *
     * <p>Equal to {@link #normMultiplier} unless somebody typed one over. Two
     * things need it and neither can reconstruct it: a screen saying "1.20,
     * izmenjeno, podrazumevano 1.10", and the payslip, which folds a category's
     * rows back into one line at this coefficient.
     */
    @Builder.Default
    @Column(name = "norm_multiplier_default", nullable = false)
    private BigDecimal normMultiplierDefault = BigDecimal.ONE;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}

