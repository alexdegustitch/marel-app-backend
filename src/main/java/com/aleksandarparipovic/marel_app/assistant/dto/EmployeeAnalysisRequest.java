package com.aleksandarparipovic.marel_app.assistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One employee, one month: what the "Spiky" assistant is asked to summarise.
 */
public record EmployeeAnalysisRequest(
        @NotNull Long employeeId,
        @NotNull @Min(1) @Max(12) Integer month,
        @NotNull Integer year
) {
}
