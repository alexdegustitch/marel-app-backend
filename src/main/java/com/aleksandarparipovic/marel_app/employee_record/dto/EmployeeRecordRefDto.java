package com.aleksandarparipovic.marel_app.employee_record.dto;

/**
 * Just enough to address a karton: which one, and which employee-month it is.
 *
 * <p>Its own tiny type rather than returning a bare id, so the caller can assert
 * it got the month it asked about — a calendar that has paged on while the
 * request was in flight must not follow an answer about the previous month.
 */
public record EmployeeRecordRefDto(
        Long employeeRecordId,
        Long employeeId,
        Integer year,
        Integer month
) {}
