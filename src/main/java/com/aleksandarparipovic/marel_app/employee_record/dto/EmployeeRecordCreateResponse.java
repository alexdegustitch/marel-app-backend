package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.util.List;

public record EmployeeRecordCreateResponse(
		int year,
		int month,
		int createdEmployeeRecords,
		List<Long> employeeRecordIds,
		List<Long> employeeIds
) {
}
