package com.aleksandarparipovic.marel_app.employee_payroll_run_item_update.dto;

import java.time.OffsetDateTime;

public record EmployeePayrollRunItemUpdateDto(
        Long id,
        Long payrollRunItemId,
        Long userId,
        String userName,
        OffsetDateTime lastActivityAt
) {}

