package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.time.Instant;

/** A karton the caller recently had open, with the month it belongs to. */
public interface EmployeeRecordRecentDto {
    Integer getMonth();
    Long getEmployeeRecordId();
    Long getEmployeeId();
    String getEmployeeName();
    Instant getUpdateTime();
}
