package com.aleksandarparipovic.marel_app.work_log;

import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.operation.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Single-log performance rate formula, shared by the recalc engine (DailyRecalcService,
// which aggregates this across a category's logs) and the analytics fact sync
// (AnalyticsFactSyncService, which stores it per-log). Keeping one implementation here
// guarantees both consumers can never diverge.
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

    public BigDecimal calculateApprovedPerformanceRate(WorkLog log) {
        BigDecimal rate = calculatePerformanceRate(log);
        return rate.min(appSettingService.getMaxEfficiencyPercentAt(log.getStartAt()));
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
