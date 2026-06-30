package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.time.Instant;

public interface EmployeeRecordDto {
    Long getEmployeeRecordId();

    String getEmployeeName();

    Long getEmployeeId();

    Integer getMonth();

    Integer getYear();

    Instant getUpdateTime();
}
