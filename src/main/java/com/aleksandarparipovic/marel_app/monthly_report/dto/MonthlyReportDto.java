package com.aleksandarparipovic.marel_app.monthly_report.dto;


import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;


@Getter
@Builder
public class MonthlyReportDto {
    private Long employeeId;
    private LocalDate workDate;
    private Integer year;
    private Integer month;
    private long totalShiftMinutes;
    private BigDecimal performanceRate;
    private BigDecimal approvedPerformanceRate;
    private BigDecimal totalWeightedNormMinutes;
    private BigDecimal totalApprovedMinutes;
}
