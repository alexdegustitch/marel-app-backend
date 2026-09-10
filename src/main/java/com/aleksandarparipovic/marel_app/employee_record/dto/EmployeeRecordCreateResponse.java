package com.aleksandarparipovic.marel_app.employee_record.dto;

import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.MaterializationConflict;

import java.util.List;

public record EmployeeRecordCreateResponse(
		int year,
		int month,
		int createdEmployeeRecords,
		List<Long> employeeRecordIds,
		List<Long> employeeIds,
		/** Leave days the creation materialised from open od–do periods. */
		int leaveCreatedShifts,
		/** Leave days it could NOT write because another shift stands on them. */
		List<MaterializationConflict> leaveConflicts
) {

	/** The record creation alone, before the leave sweep adds its part. */
	public EmployeeRecordCreateResponse(int year, int month, int createdEmployeeRecords,
			List<Long> employeeRecordIds, List<Long> employeeIds) {
		this(year, month, createdEmployeeRecords, employeeRecordIds, employeeIds, 0, List.of());
	}

	public EmployeeRecordCreateResponse withLeave(int leaveCreatedShifts,
			List<MaterializationConflict> leaveConflicts) {
		return new EmployeeRecordCreateResponse(year, month, createdEmployeeRecords,
				employeeRecordIds, employeeIds, leaveCreatedShifts, leaveConflicts);
	}
}
