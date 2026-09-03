package com.aleksandarparipovic.marel_app.payroll_run.dto;

/** A payroll month found by a fragment of the worker's name or number — as read. */
public interface PayrollRunSearchRow {
    Long getMonthlyReportId();
    Long getEmployeeId();
    String getEmployeeName();
    String getEmployeeNo();
    Integer getMonth();
    Integer getYear();
    String getStatus();
}
