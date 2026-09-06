package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.util.List;

/**
 * The active workers a month has no karton for.
 *
 * <p>{@code total} is the whole count and is what "Bez kartona" states — it is
 * also exactly how many kartoni {@code POST /employee-records/create-records}
 * would create for this month, because both read the same definition of an
 * active worker. {@code rows} is a capped page of that same set, enough to name
 * who is missing without turning this into a second way to list the register.
 *
 * @param total how many active workers have no karton for the month
 * @param rows  the first of them by name, at most the requested limit
 */
public record EmployeeRecordMissing(int year, int month, long total, List<EmployeeWithoutRecord> rows) {
}
