package com.aleksandarparipovic.marel_app.daily_report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface DailyReportChartInfo {
    LocalDate getWorkDate();

    Long getWorkShiftId();

    BigDecimal getApprovedPerformanceRate();
}

