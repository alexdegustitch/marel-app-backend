package com.aleksandarparipovic.marel_app.payroll_run.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public interface PayrollRunInfoDto {
    Long getId();
    Long getEmployeeId();
    String getEmployeeName();
    String getEmployeeNo();
    String getEmployeeDepartment();
    String getStatus();
    BigDecimal getTotalNetEarnings();
    BigDecimal getNetPayableAmount();
    Long getMonthlyReportId();
    OffsetDateTime getUpdatedAt();
}

