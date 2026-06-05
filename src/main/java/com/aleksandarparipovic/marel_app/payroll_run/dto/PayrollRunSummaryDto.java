package com.aleksandarparipovic.marel_app.payroll_run.dto;

public interface PayrollRunSummaryDto {
    Long getId();
    Long getEmployeeId();
    String getEmployeeName();
    Integer getMonth();
    Integer getYear();
}

