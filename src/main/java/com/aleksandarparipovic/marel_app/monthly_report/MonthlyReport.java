package com.aleksandarparipovic.marel_app.monthly_report;

import com.aleksandarparipovic.marel_app.employee.Employee;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "monthly_reports")
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
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "report_year", nullable = false)
    private Integer reportYear;

    @Column(name = "report_month", nullable = false)
    private Integer reportMonth;

    @Column(name = "total_shift_minutes", nullable = false)
    private Integer totalShiftMinutes;

    @Column(name = "total_work_minutes", nullable = false)
    private Integer totalWorkMinutes;

    @Column(name = "total_absence_minutes", nullable = false)
    private Integer totalAbsenceMinutes;

    @Column(name = "total_paid_absence_minutes", nullable = false)
    private Integer totalPaidAbsenceMinutes;

    @Column(name = "total_unpaid_absence_minutes", nullable = false)
    private Integer totalUnpaidAbsenceMinutes;

    @Column(name = "total_compensated_minutes", nullable = false)
    private Integer totalCompensatedMinutes;

    @Column(name = "total_approved_minutes", nullable = false)
    private Integer totalApprovedMinutes;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "total_scrap", nullable = false)
    private Integer totalScrap;

    @Column(name = "total_effective_minutes", nullable = false)
    private BigDecimal totalEffectiveMinutes;

    @Column(name = "performance_rate")
    private BigDecimal performanceRate;

    @Column(name = "approved_performance_rate")
    private BigDecimal approvedPerformanceRate;

    @Column(name = "performance_coefficient")
    private BigDecimal performanceCoefficient;

    @Column(name = "meal_allowance_num")
    private Integer mealAllowanceNum;

    @Column(name = "meal_allowance_amount")
    private BigDecimal mealAllowanceAmount;

    @Column(name = "total_meal_allowance")
    private BigDecimal totalMealAllowance;

    @Column(name = "status")
    private String status;

    @Column(name = "calc_version")
    private Integer calcVersion;

    @Column(name = "last_recalculated_at")
    private OffsetDateTime lastRecalculatedAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}

