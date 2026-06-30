package com.aleksandarparipovic.marel_app.employee_record.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmployeeRecordCreateRequest {

	@NotNull
	@Min(2000)
	@Max(2100)
	private Integer year;

	@NotNull
	@Min(1)
	@Max(12)
	private Integer month;
}
