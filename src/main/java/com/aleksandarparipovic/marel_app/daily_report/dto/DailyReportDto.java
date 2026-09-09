package com.aleksandarparipovic.marel_app.daily_report.dto;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;



@Data
public class DailyReportDto {
    private Long workShiftId;
    private Long employeeId;
    private LocalDate workDate;
    private Integer totalShiftMinutes;
    private BigDecimal performanceRate;
    private BigDecimal approvedPerformanceRate;
    private BigDecimal performanceCoefficient;
    private BigDecimal approvedPerformanceCoefficient;
    private BigDecimal totalWeightedNormMinutes;
    /** Coefficient-weighted verified time. NULL on reports not yet recalculated. */
    private BigDecimal totalVerifiedMinutes;
    private Integer totalPlMinutes;
    private Integer totalPlbMinutes;
    private Integer bonusEligibleMinutes;
    private Boolean isMealAllowed;
    private Integer mealsCount;
    /** Hand correction added to {@code mealsCount}; 0 when never touched. */
    private Integer mealsManualDelta;
    private String mealsManualNote;
    /** {@code max(0, mealsCount + mealsManualDelta)} — the figure the month pays. */
    private Integer effectiveMealsCount;
}