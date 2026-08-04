package com.aleksandarparipovic.marel_app.payroll_calculation;

/**
 * One payroll line's arithmetic.
 *
 * <p>A new kind of payslip line that uses maths the system already has is
 * configuration. A new kind of MATHS is one of these.
 */
public interface PayrollComponentCalculator {

    /** Matches {@code payroll_adjustment_categories.calculation_key}. */
    String calculationKey();

    ComponentResult calculate(ComponentContext ctx);
}
