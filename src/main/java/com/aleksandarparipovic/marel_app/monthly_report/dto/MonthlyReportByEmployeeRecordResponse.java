package com.aleksandarparipovic.marel_app.monthly_report.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class MonthlyReportByEmployeeRecordResponse {
    private Long id;
    private Integer totalShiftMinutes;
    private Integer totalAbsenceMinutes;
    private Integer totalSickLeaveMinutes;
    private BigDecimal totalWeightedNormMinutes;
    private BigDecimal approvedPerformanceRate;
    private Integer mealAllowanceNum;
}

