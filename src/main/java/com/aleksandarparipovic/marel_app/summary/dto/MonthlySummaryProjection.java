package com.aleksandarparipovic.marel_app.summary.dto;

public interface MonthlySummaryProjection {
    Long getEmployeeId();
    Integer getReportYear();
    Integer getReportMonth();
    Long getTotalShiftMinutes();
    Long getTotalWorkMinutes();
    Long getTotalQuantity();
    Long getTotalScrap();
    Object getTotalEffectiveMinutes();
}
