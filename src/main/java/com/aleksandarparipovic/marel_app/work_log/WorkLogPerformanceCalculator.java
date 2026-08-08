package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.operation.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single-log performance formula. Both the measured rate and the paid one.
 *
 * <p>Shared by the recalc engine ({@code DailyRecalcService}, which aggregates it
 * across a category's logs) and the analytics fact sync
 * ({@code AnalyticsFactSyncService}, which stores it per log).
 *
 * <p><b>This class used to claim its consumers could not diverge, and they had.</b>
 * {@code DailyRecalcService.computeWeightedRates} reimplemented
 * {@code rate.min(ceiling)} inline instead of calling
 * {@link #calculateApprovedPerformanceRate}, so the payroll and the analytics
 * computed the paid rate from two copies of one rule. Probation is what exposed
 * it: added here alone, analytics would have credited 100 % and the payslip would
 * not. The duplicate is gone and the claim is true again — do not reintroduce it.
 *
 * <p>The frontend keeps its own copy for the optimistic preview in
 * {@code EmployeeShiftOperationRow}. That one cannot be shared, so it is a place
 * to change deliberately whenever this changes.
 */
@Component
@RequiredArgsConstructor
public class WorkLogPerformanceCalculator {

    private final AppSettingService appSettingService;

    public BigDecimal calculatePerformanceRate(WorkLog log) {
        Operation operation = log.getOperation();
        if (operation == null || !operation.isNormRequired()) {
            return BigDecimal.valueOf(100);
        }

        BigDecimal hourlyOutput = defaultDecimal(log.getHourlyOutput());
        if (hourlyOutput.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(100);
        }

        return BigDecimal.valueOf(100)
                .multiply(hourlyOutput)
                .divide(BigDecimal.valueOf(operation.getMinNorm()), 6, RoundingMode.HALF_UP);
    }

    /**
     * What is actually PAID for this log.
     *
     * @param onProbation whether the employee was inside their probation period on
     *   the SHIFT's work date. Resolved once per shift by the caller, not looked up
     *   here: every log of a shift shares one employee and one work date, and a
     *   query per log would be an N+1 inside the recalculation loop.
     *
     *   <p>It must come from the shift's {@code work_date}, never from the log's
     *   own {@code start_at}: a night shift crosses midnight, so its after-midnight
     *   logs would fall on the next calendar day and a shift starting on the last
     *   day of probation would be credited half one way and half the other.
     *
     * <p><b>On probation, 100 % is substituted for the measured rate and the
     * ordinary ceiling still applies.</b> So the result is
     * {@code min(100, max_efficiency_percent)} — if the ceiling is ever set below
     * 100 the ceiling wins, which is the owner's rule. Probation replaces what was
     * measured, not the limit on what may be paid.
     *
     * <p>Note this moves the figure in BOTH directions, which is why it cannot be
     * expressed as another ceiling: 35 against a norm of 40 is 87.5 % and becomes
     * 100, and 50 against the same norm is 125 % and also becomes 100.
     */
    public BigDecimal calculateApprovedPerformanceRate(WorkLog log, boolean onProbation) {
        BigDecimal rate = onProbation
                ? BigDecimal.valueOf(100)
                : calculatePerformanceRate(log);
        return rate.min(appSettingService.getMaxEfficiencyPercentAt(log.getStartAt()));
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
