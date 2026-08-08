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
    private String firstName;

    @NotBlank
    private String lastName;

    @NotNull
    private Long departmentId;

    @NotNull
    private Long categoryId;

    @Min(0)
    private BigDecimal transportAllowanceRsd;

    private String transportAllowanceMode;

    @NotNull
    private LocalDate employmentStartDate;

    private String notes;

    private String mobilePhone;

    private String email;

    @Min(0)
    private BigDecimal hourlyRate;

    private Long defaultWorkCategoryId;

}
