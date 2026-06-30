package com.aleksandarparipovic.marel_app.daily_report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DailyReportEmployeeMonthlyInfo {
    LocalDate getWorkDate();

    Integer getTotalApprovedMinutes();

    BigDecimal getApprovedPerformanceRate();

    Integer getMealsCount();

    Integer getTotalShiftMinutes();
}
