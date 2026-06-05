package com.aleksandarparipovic.marel_app.work_shift.dto;

import java.time.Instant;

public interface WorkShiftActivityDto {
    Long getEmployeeRecordId();
    String getEmployeeName();
    Long getEmployeeId();
    Integer getMonth();
    Integer getYear();
    Instant getUpdateTime();
}

