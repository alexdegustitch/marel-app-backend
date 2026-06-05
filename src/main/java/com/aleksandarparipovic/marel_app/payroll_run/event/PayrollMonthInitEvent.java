package com.aleksandarparipovic.marel_app.payroll_run.event;

import java.util.List;

/**
 * Published after employee records are created for a month.
 * Triggers async bulk initialization of monthly reports, payroll run, items, categories and adjustments.
 */
public record PayrollMonthInitEvent(
        int year,
        int month,
        List<Long> employeeRecordIds,
        Long initiatedByUserId
) {}

