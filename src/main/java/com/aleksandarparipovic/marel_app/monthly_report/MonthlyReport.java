package com.aleksandarparipovic.marel_app.monthly_report;

import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "monthly_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_monthly_reports_employee_record_period",
                columnNames = {"employee_record_id", "start_date", "end_date"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_record_id", nullable = false)
    private EmployeeRecord employeeRecord;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "total_shift_minutes", nullable = false)
    private Integer totalShiftMinutes;

    @Column(name = "total_work_minutes", nullable = false)
    private Integer totalWorkMinutes;

    @Column(name = "total_absence_paid_minutes", nullable = false)
    private Integer totalAbsencePaidMinutes;

    @Column(name = "total_absence_unpaid_minutes", nullable = false)
    private Integer totalAbsenceUnpaidMinutes;

    @Column(name = "total_absence_minutes", nullable = false)
    private Integer totalAbsenceMinutes;

    @Column(name = "total_sick_leave_paid_minutes", nullable = false)
    private Integer totalSickLeavePaidMinutes;

    @Column(name = "total_sick_leave_unpaid_minutes", nullable = false)
    private Integer totalSickLeaveUnpaidMinutes;

    @Column(name = "total_sick_leave_minutes", nullable = false)
    private Integer totalSickLeaveMinutes;

    @Column(name = "total_approved_minutes", nullable = false)
    private Integer totalApprovedMinutes;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "total_scrap", nullable = false)
    private Integer totalScrap;

    @Column(name = "total_weighted_norm_minutes", nullable = false)
    private BigDecimal totalWeightedNormMinutes;

    @Column(name = "performance_rate")
    private BigDecimal performanceRate;

    @Column(name = "approved_performance_rate")
    private BigDecimal approvedPerformanceRate;

    @Column(name = "performance_coefficient")
    private BigDecimal performanceCoefficient;

    @Column(name = "approved_performance_coefficient")
    private BigDecimal approvedPerformanceCoefficient;

    @Column(name = "meal_allowance_num")
    private Integer mealAllowanceNum;

    @Column(name = "status")
    private String status;

    @Column(name = "calc_version")
    private Integer calcVersion;

    @Column(name = "last_recalculated_at")
    private OffsetDateTime lastRecalculatedAt;

    @Column(name = "created_at", updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
