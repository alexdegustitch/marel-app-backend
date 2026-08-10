package com.aleksandarparipovic.marel_app.employee_payroll_value.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * What an existing period should have said.
 *
 * <p>No date: correcting a figure and moving a period are different operations
 * with different consequences for the months around it. Moving one is removing
 * it and adding another, and both of those already exist.
 */
@Getter
@Setter
public class CorrectEmployeePayrollValueRequest {

    /** For a NUMERIC period. The period's own type decides which is read. */
    @PositiveOrZero
    private BigDecimal numericValue;

    /** For a BOOLEAN period. */
    private Boolean booleanValue;

    /** Why it was corrected. Absent leaves the existing note alone. */
    private String note;
}
