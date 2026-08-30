package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Getter
public class PayrollRunItemResponse {
    private final Long id;
    private final Long payrollRunId;
    private final Long employeeId;
    private final Long monthlyReportId;
    private final LocalDate period;

    // ── Work-log-derived totals ──────────────────────────────────────────────
    private final Integer totalShiftMinutes;
    private final Integer totalWorkMinutes;
    private final Integer totalAbsenceMinutes;
    private final Integer totalPaidAbsenceMinutes;
    private final Integer totalUnpaidAbsenceMinutes;
    private final Integer totalCompensatedMinutes;
    private final Integer totalApprovedMinutes;
    private final Integer totalQuantity;
    private final Integer totalScrap;
    private final BigDecimal totalEffectiveMinutes;
    private final BigDecimal performanceRate;
    private final BigDecimal approvedPerformanceRate;

    /**
     * The efficiency ceiling this month was measured against — max_efficiency_percent
     * as it stood on the LAST DAY of the period.
     *
     * <p>Sent so the screen does not have to guess. The monthly efficiency bar had
     * 120 % written into it, which was neither the configured limit nor tied to the
     * month being shown: a factory that raised the ceiling saw the same scale, and
     * an old month was drawn against today's number.
     *
     * <p>Not final and set after construction, because it comes from app_settings
     * rather than from the item, and a DTO has no business reaching for a service.
     */
    @Setter
    private BigDecimal maxEfficiencyPercent;
    private final BigDecimal performanceCoefficient;
    private final Integer totalWorkDays;
    private final Integer totalPaidDays;
    private final Integer totalAbsenceDays;

    // ── Payroll minutes ──────────────────────────────────────────────────────
    private final Integer manualAdjustedMinutes;
    private final Integer totalPayrollMinutes;

    // ── Rate & base pay ──────────────────────────────────────────────────────
    /*
     * Adjustable ONLY on the response, never on the entity.
     *
     * A reader who may not see every line gets totals evaluated over the lines
     * they may see. That figure belongs to the answer, not to the payroll — the
     * item in the database keeps the real ones, and writing them here would
     * flush a filtered total into the record itself.
     */
    @Setter
    private BigDecimal totalNetEarnings;
    @Setter
    private BigDecimal hourlyRate;

    /**
     * The director's note for this month's payslip.
     *
     * <p>{@code @Setter} so the service can withhold it: everybody without
     * PAYROLL_DIRECTOR_NOTE gets null, the same way the hidden money figures are
     * withheld rather than hidden by the browser.
     */
    @Setter
    private String directorNote;
    private final BigDecimal hourlyRateSystem;
    private final Boolean hourlyRateOverridden;

    // ── The performance mark, and what it is doing to the rate ──────────────

    /** The ocena, 0–2. Null when nobody gave one — the screen shows a dash. */
    private final BigDecimal performanceMark;
    /** Who gave it, so the number is attributable without opening the audit log. */
    private final String performanceMarkByName;
    /** Whether hourlyRate above is currently the base multiplied by the mark. */
    private final Boolean performanceMarkApplied;
    private final String performanceMarkAppliedByName;
    /**
     * The rate WITHOUT the mark — what "primeni" multiplies and what "vrati"
     * returns to.
     *
     * <p>Sent rather than left to the client to work out: dividing hourlyRate
     * back by the mark loses cents to rounding, and the screen has to be able to
     * print "bilo 500,00" exactly.
     */
    private final BigDecimal hourlyRateBase;

    // ── Meal allowance ───────────────────────────────────────────────────────

    // ── Transport allowance ──────────────────────────────────────────────────

    // ── Bonus components ─────────────────────────────────────────────────────

    // ── Calculated summary totals ─────────────────────────────────────────────
    @Setter
    private BigDecimal previouslyPaidAmount;
    private final BigDecimal previousNetPayableAmount;
    @Setter
    private BigDecimal currentBalanceAmount;
    @Setter
    private BigDecimal netPayableAmount;

    // ── Metadata ─────────────────────────────────────────────────────────────
    private final String status;
    private final String currencyCode;
    private final Integer calcVersion;
    private final String note;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;
    private final Integer basedOnVersion;
    private final Boolean needsRecalculation;
    private final OffsetDateTime lockedAt;
    private final Long lockedBy;

    public PayrollRunItemResponse(PayrollRunItem item) {
        this.id = item.getId();
        this.payrollRunId = item.getPayrollRun() != null ? item.getPayrollRun().getId() : null;
        this.employeeId = item.getEmployee() != null ? item.getEmployee().getId() : null;
        this.monthlyReportId = item.getMonthlyReport() != null ? item.getMonthlyReport().getId() : null;
        this.period = item.getPeriod();

        this.totalShiftMinutes = item.getTotalShiftMinutes();
        this.totalWorkMinutes = item.getTotalWorkMinutes();
        this.totalAbsenceMinutes = item.getTotalAbsenceMinutes();
        this.totalPaidAbsenceMinutes = item.getTotalPaidAbsenceMinutes();
        this.totalUnpaidAbsenceMinutes = item.getTotalUnpaidAbsenceMinutes();
        this.totalCompensatedMinutes = item.getTotalCompensatedMinutes();
        this.totalApprovedMinutes = item.getTotalApprovedMinutes();
        this.totalQuantity = item.getTotalQuantity();
        this.totalScrap = item.getTotalScrap();
        this.totalEffectiveMinutes = item.getTotalEffectiveMinutes();
        this.performanceRate = item.getPerformanceRate();
        this.approvedPerformanceRate = item.getApprovedPerformanceRate();
        this.performanceCoefficient = item.getPerformanceCoefficient();
        this.totalWorkDays = item.getTotalWorkDays();
        this.totalPaidDays = item.getTotalPaidDays();
        this.totalAbsenceDays = item.getTotalAbsenceDays();

        this.manualAdjustedMinutes = item.getManualAdjustedMinutes();
        this.totalPayrollMinutes = item.getTotalPayrollMinutes();

        this.totalNetEarnings = item.getTotalNetEarnings();
        this.hourlyRate = item.getHourlyRate();
        this.directorNote = item.getDirectorNote();
        this.hourlyRateSystem = item.getHourlyRateSystem();
        this.hourlyRateOverridden = item.getHourlyRateOverridden();
        this.performanceMark = item.getPerformanceMark();
        this.performanceMarkByName = item.getPerformanceMarkBy() != null
                ? item.getPerformanceMarkBy().getFullName() : null;
        this.performanceMarkApplied = Boolean.TRUE.equals(item.getPerformanceMarkApplied());
        this.performanceMarkAppliedByName = item.getPerformanceMarkAppliedBy() != null
                ? item.getPerformanceMarkAppliedBy().getFullName() : null;
        this.hourlyRateBase = item.baseHourlyRate();




        this.previouslyPaidAmount = item.getPreviouslyPaidAmount();
        this.previousNetPayableAmount = item.getPreviousNetPayableAmount();
        this.currentBalanceAmount = item.getCurrentBalanceAmount();
        this.netPayableAmount = item.getNetPayableAmount();

        this.status = item.getStatus();
        this.currencyCode = item.getCurrencyCode();
        this.calcVersion = item.getCalcVersion();
        this.note = item.getNote();
        this.createdAt = item.getCreatedAt();
        this.updatedAt = item.getUpdatedAt();
        this.archivedAt = item.getArchivedAt();
        this.basedOnVersion = item.getBasedOnVersion();
        this.needsRecalculation = item.getNeedsRecalculation();
        this.lockedAt = item.getLockedAt();
        this.lockedBy = item.getLockedBy();
    }
}
