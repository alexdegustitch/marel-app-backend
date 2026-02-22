package com.aleksandarparipovic.marel_app.employee.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class EmployeeEditRequest {
    @NotBlank
    private String employeeNo;

    @NotBlank
    private String fullName;

    @NotNull
    private Long departmentId;

    @NotNull
    private Long categoryId;

    @NotNull
    private Boolean foreigner;

    @Min(0)
    private BigDecimal transportAllowanceRsd;

    @NotNull
    private LocalDate employmentStartDate;

    private String notes;
}
