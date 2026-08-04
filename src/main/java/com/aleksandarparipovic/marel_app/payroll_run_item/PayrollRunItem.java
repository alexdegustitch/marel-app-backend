package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.monthly_report.MonthlyReport;
import com.aleksandarparipovic.marel_app.payroll_run.PayrollRun;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payroll_run_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayrollRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monthly_report_id")
    private MonthlyReport monthlyReport;

    /** First day of the month this payroll item covers. */
    @Column(name = "period")
    private LocalDate period;

    // ── Work-log-derived totals ─────────────────────────────────────────────

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

    @Column(name = "total_work_days", nullable = false)
    private Integer totalWorkDays;

    @Column(name = "total_paid_days", nullable = false)
    private Integer totalPaidDays;

    @Column(name = "total_absence_days", nullable = false)
    private Integer totalAbsenceDays;

    // ── Payroll minutes ─────────────────────────────────────────────────────

    /**
     * Manually entered minute adjustment (positive or negative).
     * Used to increase or decrease the calculated payroll minutes.
     */
    @Column(name = "manual_adjusted_minutes")
    private Integer manualAdjustedMinutes;

    /**
     * total_work_minutes + manual_adjusted_minutes
     */
    @Column(name = "total_payroll_minutes")
    private Integer totalPayrollMinutes;

    // ── Rate & base pay ─────────────────────────────────────────────────────


    /** Gross earnings minus deductions (net before payment deductions) */
    @Column(name = "total_net_earnings")
    private BigDecimal totalNetEarnings;

    @Column(name = "hourly_rate", nullable = false)
    private BigDecimal hourlyRate;

    @Column(name = "hourly_rate_system", nullable = false)
    private BigDecimal hourlyRateSystem;

    @Column(name = "hourly_rate_overridden", nullable = false)
    private Boolean hourlyRateOverridden;

    // ── Meal allowance ──────────────────────────────────────────────────────






    // ── Transport allowance ─────────────────────────────────────────────────






    // ── Bonus components ────────────────────────────────────────────────────










    // ── Calculated summary totals ───────────────────────────────────────────

    /** SUM(adjustments.amount where impact_code = GROSS_PLUS and is_applied = true) */

    /** SUM(adjustments.amount where impact_code = DEDUCTION_MINUS and is_applied = true) */
    @Column(name = "total_deductions_amount")
    private BigDecimal totalDeductionsAmount;

    /** Telefon za tekući mesec — snapshot za prikaz u obračunu */
    @Column(name = "current_month_telephone")
    private BigDecimal currentMonthTelephone;

    /** SUM(adjustments.amount where impact_code = PAYMENT_MINUS and is_applied = true) */
    @Column(name = "previously_paid_amount", nullable = false)
    private BigDecimal previouslyPaidAmount;

    /** net_payable_amount from the previous payroll period for this employee */
    @Column(name = "previous_net_payable_amount")
    private BigDecimal previousNetPayableAmount;

    /**
     * total_net_earnings - previously_paid_amount.
     *
     * <p>NOT what the old comment here claimed. It named total_gross_earnings,
     * which no code path ever computes and which is 0.00 in every row, and
     * total_deductions_amount, which this does not subtract — deductions already
     * reach the figure through the adjustment rows.
     */
    @Column(name = "current_balance_amount", nullable = false)
    private BigDecimal currentBalanceAmount;

    /**
     * previous_net_payable_amount + current_balance_amount — NOT just the current
     * balance, as the old comment said. This is the link between months: it
     * becomes the next period's previous_net_payable_amount, so an unpaid balance
     * carries forward instead of being forgotten.
     */
    @Column(name = "net_payable_amount", nullable = false)
    private BigDecimal netPayableAmount;

    // ── Metadata ────────────────────────────────────────────────────────────

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "currency_code", nullable = false)
    private String currencyCode;

    @Column(name = "calc_version", nullable = false)
    private Integer calcVersion;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;


    @Column(name = "based_on_version")
    private Integer basedOnVersion;

    /**
     * Set to {@code true} when employee data that affects payroll calculation changes
     * (e.g. hourly rate, transport allowance, bonus category) — independently of
     * the monthly report version. Cleared back to {@code false} after recalculation.
     */
    @Column(name = "needs_recalculation", nullable = false)
    private Boolean needsRecalculation = false;


    @Column(name = "locked_at")
    private OffsetDateTime lockedAt;

    @Column(name = "locked_by")
    private Long lockedBy;
}
