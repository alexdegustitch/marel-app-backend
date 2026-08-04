package com.aleksandarparipovic.marel_app.employee_payroll_value.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class ChangeEmployeePayrollValueRequest {

    /** A registered definition code — see {@code EmployeePayrollValueCodes}. */
    @NotBlank
    private String code;

    @NotNull
    @PositiveOrZero
    private BigDecimal numericValue;

    /**
     * First day the new value applies. The period covering it is closed the day
     * before, so nothing already calculated moves.
     */
    @NotNull
    private LocalDate effectiveFrom;

    private String note;
}
