package com.aleksandarparipovic.marel_app.payroll_run.dto;

import java.time.Instant;

/** An obračun the caller recently had open, with the month it belongs to. */
public interface PayrollRecentDto {
    Integer getMonth();
    Long getMonthlyReportId();
    Long getEmployeeId();
    String getEmployeeName();
    Instant getUpdateTime();
}
