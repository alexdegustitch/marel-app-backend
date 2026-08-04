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

    /**
     * For a NUMERIC definition. Exactly one of this and {@link #booleanValue} is
     * given, and it must match the definition's declared type — the service
     * refuses a mismatch rather than guessing.
     */
    @PositiveOrZero
    private BigDecimal numericValue;

    /**
     * For a BOOLEAN definition — an entitlement with a start date.
     *
     * <p>{@code TRANSPORT_PER_DAY} is the first: having it TRUE and in force is
     * what puts an employee on the per-day transport mode, and setting it FALSE
     * from a date is how somebody stops being on it without their earlier months
     * changing (OPEN-15).
     */
    private Boolean booleanValue;

    /**
     * First day the new value applies. The period covering it is closed the day
     * before, so nothing already calculated moves.
     */
    @NotNull
    private LocalDate effectiveFrom;

    private String note;
}
