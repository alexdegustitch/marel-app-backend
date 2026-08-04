package com.aleksandarparipovic.marel_app.work_category_resolution;

/**
 * The configuration cannot answer a question the calculation must ask.
 *
 * <p>Thrown rather than defaulted, on purpose. Every plausible default is wrong in
 * a way nobody notices: guessing "allowed" pays money the policy may forbid,
 * guessing "excluded" removes a line from somebody's payslip, and guessing "the
 * first scheme we found" is arbitrary. A payroll run that stops gets fixed; a
 * payroll run that guesses gets discovered by the employee.
 */
public class IncompletePayrollConfigurationException extends RuntimeException {

    public IncompletePayrollConfigurationException(String message) {
        super(message);
    }
}
