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
     * The manual minute correction, positive or negative — DERIVED, not stored.
     *
     * <p>The corrections are rows in {@code payroll_time_adjustments}: one per
     * cause, each with its reason and its own audit trail. This is their applied,
     * unarchived sum, which is all a single integer could ever have expressed.
     *
     * <p>It was a column beside those rows, written from them on every save. The
     * two never disagreed — 0 drift across 849 items — but nothing except that
     * discipline made them agree, and a column that has to be kept in step with a
     * table is the same double bookkeeping the meal, transport, bonus and phone
     * mirrors were dropped to end.
     *
     * <p>Filled by the service, which is the layer that can read the rows, and in
     * one batched query for lists. Zero rather than null: the screen divides it by
     * 60, and "no correction" is 0.
     */
    @Transient
    @Builder.Default
    private Integer manualAdjustedMinutes = 0;

    /**
     * total_work_minutes + the applied minute corrections
     */
    @Column(name = "total_payroll_minutes")
    private Integer totalPayrollMinutes;

    // ── Rate & base pay ─────────────────────────────────────────────────────


    /** Gross earnings minus deductions (net before payment deductions) */
    @Column(name = "total_net_earnings")
    private BigDecimal totalNetEarnings;

    /**
     * The rate this month was actually calculated at — DERIVED from the three
     * fields below, and written to the row so every downstream reader (the
     * categories, the totals, the payslip, the reports) goes on reading one
     * column that means one thing.
     *
     * <p>Never set directly. {@link #baseHourlyRate()} and
     * {@link #effectiveHourlyRate()} are the derivation, and
     * {@code PayrollRunItemService.applyDerivedHourlyRate} is the only place
     * that writes it.
     */
    @Column(name = "hourly_rate", nullable = false)
    private BigDecimal hourlyRate;

    /** What the employee's rate history says the rate was for this period. */
    @Column(name = "hourly_rate_system", nullable = false)
    private BigDecimal hourlyRateSystem;

    /**
     * The rate a PERSON typed. Null means nobody did and the system rate stands.
     *
     * <p>Separate from {@link #hourlyRate} because the two stop being the same
     * thing the moment a mark is applied: without it, applying a mark would
     * overwrite the typed value and applying it twice would compound.
     *
     * <p>Stored as null when the typed figure EQUALS the system one, so that
     * typing the system value still reads as "not overridden" — the behaviour
     * this column replaced.
     */
    @Column(name = "hourly_rate_manual")
    private BigDecimal hourlyRateManual;

    /**
     * Whether a person typed the rate — {@code hourlyRateManual != null}.
     *
     * <p>Kept as a column because the screens and the payslip read it, and
     * because it is what the partial audit trigger watches. It is NOT the same
     * question as "does the rate differ from the system's": a rate raised by an
     * applied mark differs from the system's and was typed by nobody.
     */
    @Column(name = "hourly_rate_overridden", nullable = false)
    private Boolean hourlyRateOverridden;

    // ── Performance mark (ocena) ────────────────────────────────────────────

    /**
     * The ocena, between 0 and 2.
     *
     * <p>Given by a supervisor or an administrator; it changes NOTHING on its
     * own. Multiplying the rate by it is {@link #performanceMarkApplied}, which
     * is a separate decision by a separate person — the administrator.
     */
    @Column(name = "performance_mark")
    private BigDecimal performanceMark;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_mark_by")
    private com.aleksandarparipovic.marel_app.user.User performanceMarkBy;

    @Column(name = "performance_mark_at")
    private OffsetDateTime performanceMarkAt;

    /**
     * Whether {@link #hourlyRate} is currently the base multiplied by the mark.
     *
     * <p>Cleared whenever the rate is typed by hand, because the value then no
     * longer comes from the mark and "vrati na prethodnu vrednost" would
     * otherwise restore a figure from before that edit.
     */
    @Column(name = "performance_mark_applied", nullable = false)
    private Boolean performanceMarkApplied = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_mark_applied_by")
    private com.aleksandarparipovic.marel_app.user.User performanceMarkAppliedBy;

    @Column(name = "performance_mark_applied_at")
    private OffsetDateTime performanceMarkAppliedAt;

    /**
     * The rate before any mark — what "primeni" multiplies and what "vrati"
     * returns to.
     *
     * <p>Not a stored snapshot of the pre-mark value, deliberately. The system
     * rate can move while a mark is applied; a snapshot would then have "vrati"
     * restore a figure that is no longer anybody's rate, and the mark would
     * silently stop tracking. Read fresh, the mark re-applies to whatever the
     * base is now.
     */
    public BigDecimal baseHourlyRate() {
        BigDecimal base = hourlyRateManual != null ? hourlyRateManual : hourlyRateSystem;
        return base != null ? base : BigDecimal.ZERO;
    }

    /** The base, multiplied by the mark when — and only when — it is applied. */
    public BigDecimal effectiveHourlyRate() {
        BigDecimal base = baseHourlyRate();
        if (!Boolean.TRUE.equals(performanceMarkApplied) || performanceMark == null) {
            return base.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return base.multiply(performanceMark).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ── Meal allowance ──────────────────────────────────────────────────────






    // ── Transport allowance ─────────────────────────────────────────────────






    // ── Bonus components ────────────────────────────────────────────────────










    // ── Calculated summary totals ───────────────────────────────────────────

    /** SUM(adjustments.amount where impact_code = GROSS_PLUS and is_applied = true) */


    /** Telefon za tekući mesec — snapshot za prikaz u obračunu */

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

    /**
     * The note printed on THIS MONTH'S payslip under "Napomena Direktora".
     *
     * <p>Rich text, like {@link #note}. Distinct from the worker's standing
     * general note ({@code employees.notes}), which the payslip used to print
     * under that heading and no longer does — a heading that names one thing and
     * prints another is worse than no heading.
     *
     * <p>Withheld from anybody without PAYROLL_DIRECTOR_NOTE by the service, not
     * by the schema.
     */
    @Column(name = "director_note")
    private String directorNote;

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
