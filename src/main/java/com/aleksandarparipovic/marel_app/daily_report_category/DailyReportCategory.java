package com.aleksandarparipovic.marel_app.daily_report_category;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "daily_report_categories")
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

    @Column(name = "total_compensated_minutes")
    private Integer totalCompensatedMinutes;

    @Column(name = "total_approved_minutes")
    private Integer totalApprovedMinutes;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "total_scrap", nullable = false)
    private Integer totalScrap;

    @Column(name = "total_weighted_norm_minutes", nullable = false)
    private BigDecimal totalWeightedNormMinutes;

    @Column(name = "performance_rate", nullable = false)
    private BigDecimal performanceRate;

    @Column(name = "approved_performance_rate", nullable = false)
    private BigDecimal approvedPerformanceRate;

    @Column(name = "category_coefficient_snapshot", nullable = false)
    private BigDecimal categoryCoefficientSnapshot;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}

