package com.aleksandarparipovic.marel_app.monthly_report_category;

import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "monthly_report_categories",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_monthly_report_category_report_category",
                columnNames = {"monthly_report_id", "work_code_category_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyReportCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monthly_report_id", nullable = false)
    private MonthlyReport monthlyReport;

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

    @Column(name = "total_approved_minutes")
    private BigDecimal totalApprovedMinutes;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;
}

