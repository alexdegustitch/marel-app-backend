package com.aleksandarparipovic.marel_app.daily_report;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "daily_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "work_date")
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_shift_id", nullable = false)
    private WorkShift workShift;

    @Column(name = "total_shift_minutes")
    private Integer totalShiftMinutes;

    @Column(name = "total_work_minutes")
    private Integer totalWorkMinutes;

    @Column(name = "total_absence_minutes")
    private Integer totalAbsenceMinutes;

    @Column(name = "total_paid_absence_minutes")
    private Integer totalPaidAbsenceMinutes;

    @Column(name = "total_unpaid_absence_minutes")
    private Integer totalUnpaidAbsenceMinutes;

    @Column(name = "total_compensated_minutes")
    private Integer totalCompensatedMinutes;

    @Column(name = "total_approved_minutes")
    private Integer totalApprovedMinutes;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "total_scrap")
    private Integer totalScrap;

    @Column(name = "total_weighted_norm_minutes")
    private BigDecimal totalWeightedNormMinutes;

    @Column(name = "performance_rate")
    private BigDecimal performanceRate;

    @Column(name = "approved_performance_rate")
    private BigDecimal approvedPerformanceRate;

    @Column(name = "performance_coefficient")
    private BigDecimal performanceCoefficient;

    @Column(name = "calc_version", nullable = false)
    private Integer calcVersion;

    @Column(name = "last_recalculated_at")
    private OffsetDateTime lastRecalculatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Column(name = "meal_allowance_num")
    private Integer mealAllowanceNum;
}

