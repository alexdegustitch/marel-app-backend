package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.math.BigDecimal;
import java.time.Instant;

public interface EmployeeRecordInfo {
    Long getId();

    String getEmployeeName();

    Long getEmployeeId();

    String getEmployeeNo();

    Boolean getEmployeeForeigner();

    String getEmployeeDepartment();

    String getEmployeeBonus();

    Instant getUpdateTime();

    Integer getTotalShiftMinutes();

    BigDecimal getApprovedPerformanceRate();
}
