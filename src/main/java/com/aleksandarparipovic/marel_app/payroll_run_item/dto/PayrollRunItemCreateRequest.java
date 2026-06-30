package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayrollRunItemCreateRequest {

	@NotNull
	private Long payrollRunId;

	@NotNull
	private Long employeeId;

	private Long monthlyReportId;
}

