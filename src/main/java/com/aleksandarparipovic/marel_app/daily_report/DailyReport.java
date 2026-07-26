package com.aleksandarparipovic.marel_app.daily_report;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "daily_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_daily_reports_employee_shift",
                columnNames = {"employee_id", "work_shift_id"}
        )
)
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

    @Column(name = "total_absence_paid_minutes")
    private Integer totalAbsencePaidMinutes;

    @Column(name = "total_absence_unpaid_minutes")
    private Integer totalAbsenceUnpaidMinutes;

    @Column(name = "total_sick_leave_paid_minutes")
    private Integer totalSickLeavePaidMinutes;

    @Column(name = "total_sick_leave_unpaid_minutes")
    private Integer totalSickLeaveUnpaidMinutes;

    @Column(name = "total_compensated_minutes")
    private Integer totalCompensatedMinutes;

    @Column(name = "total_approved_minutes")
    private Integer totalApprovedMinutes;

    @Column(name = "bonus_eligible_minutes", nullable = false)
    private Integer bonusEligibleMinutes;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "total_scrap")
    private Integer totalScrap;

    @Column(name = "total_weighted_norm_minutes")
    private BigDecimal totalWeightedNormMinutes;

    /**
     * Coefficient-weighted verified time: Σ over non-overlapping covered intervals of
     * interval duration × the PL/PLB coefficient in force. Distinct from
     * {@link #totalWeightedNormMinutes}, which stays weighted by approved performance.
     * NULL on reports last calculated before this field existed.
     */
    @Column(name = "total_verified_minutes")
    private BigDecimal totalVerifiedMinutes;

    /** Covered minutes classified PL (below the PLB concurrency threshold). */
    @Column(name = "total_pl_minutes")
    private Integer totalPlMinutes;

    /** Covered minutes classified PLB (three or more parallel-capable logs active). */
    @Column(name = "total_plb_minutes")
    private Integer totalPlbMinutes;

    @Column(name = "performance_rate")
    private BigDecimal performanceRate;

    @Column(name = "approved_performance_rate")
    private BigDecimal approvedPerformanceRate;

    @Column(name = "performance_coefficient")
    private BigDecimal performanceCoefficient;

    @Column(name = "approved_performance_coefficient")
    private BigDecimal approvedPerformanceCoefficient;

    @Column(name = "calc_version", nullable = false)
    private Integer calcVersion;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "last_recalculated_at")
    private OffsetDateTime lastRecalculatedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Builder.Default
    @Column(name = "is_meal_allowed", nullable = false)
    private Boolean isMealAllowed = false;

    @Builder.Default
    @Column(name = "meals_count", nullable = false)
    private Integer mealsCount = 0;

}

