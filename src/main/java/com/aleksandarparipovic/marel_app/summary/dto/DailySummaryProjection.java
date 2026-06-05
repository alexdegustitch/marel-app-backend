package com.aleksandarparipovic.marel_app.summary.dto;

import java.time.LocalDate;

public interface DailySummaryProjection {
    Long getWorkShiftId();
    Long getEmployeeId();
    LocalDate getWorkDate();
    Integer getTotalShiftMinutes();
    Long getTotalWorkMinutes();
    Long getTotalQuantity();
    Long getTotalScrap();
}
