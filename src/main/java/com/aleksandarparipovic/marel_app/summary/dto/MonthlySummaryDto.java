package com.aleksandarparipovic.marel_app.summary.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class MonthlySummaryDto {
    private Long employeeId;
    private LocalDate startDate;
    private LocalDate endDate;
    private long totalShiftMinutes;
    private BigDecimal performanceRate;
    private BigDecimal approvedPerformanceRate;
    private BigDecimal totalWeightedNormMinutes;
    private BigDecimal totalApprovedMinutes;
    /** True when monthly data may still be stale (daily worker not yet finished). */
    private boolean stale;
}
