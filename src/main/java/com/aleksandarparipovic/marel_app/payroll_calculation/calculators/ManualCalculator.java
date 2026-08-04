package com.aleksandarparipovic.marel_app.payroll_calculation.calculators;

import com.aleksandarparipovic.marel_app.payroll_calculation.CalculationKeys;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentContext;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentResult;
import com.aleksandarparipovic.marel_app.payroll_calculation.PayrollComponentCalculator;
import org.springframework.stereotype.Component;

/**
 * A line with no automatic value: the amount is whatever a user entered.
 *
 * <p>Registered rather than left as a null key so that
 * {@link com.aleksandarparipovic.marel_app.payroll_calculation.PayrollCalculatorRegistry}
 * can treat "no calculator for this key" as the error it is. "This line is
 * deliberately manual" and "somebody forgot to write the calculator" must not
 * look the same.
 *
 * <p>It returns {@link ComponentResult#unchanged()}, which is not zero — writing
 * zero would wipe the amount on every recalculation.
 */
@Component
public class ManualCalculator implements PayrollComponentCalculator {

    @Override
    public String calculationKey() {
        return CalculationKeys.MANUAL;
    }

    @Override
    public ComponentResult calculate(ComponentContext ctx) {
        return ComponentResult.unchanged();
    }
}
