package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import java.time.Instant;

public interface PayrollRunItemActivityDto {
    Long getMonthlyReportId();
    Long getEmployeeId();
    String getEmployeeName();
    Integer getMonth();
    Integer getYear();
    Instant getUpdateTime();
}

