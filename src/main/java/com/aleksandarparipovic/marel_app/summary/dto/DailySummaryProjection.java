package com.aleksandarparipovic.marel_app.summary.dto;

public interface DailySummaryProjection {
    Long getWorkShiftId();
    Long getEmployeeId();
    Integer getTotalShiftMinutes();
}
