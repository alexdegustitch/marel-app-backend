package com.aleksandarparipovic.marel_app.payroll_calculation.calculators;

import com.aleksandarparipovic.marel_app.payroll_calculation.CalculationKeys;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentContext;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentResult;
import com.aleksandarparipovic.marel_app.payroll_calculation.PayrollComponentCalculator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Prethodno stanje" — last month's closing balance, carried into this one.
 *
 * <pre>
 *   netPayable = previousNetPayableAmount + currentBalance
 * </pre>
 *
 * <p>The value is already on the item: {@code previous_net_payable_amount}, copied
 * from the previous month's {@code net_payable_amount} at recalculation. This line
 * shows it, so the payslip can explain how the amount to pay was reached instead
 * of presenting a total with an unexplained opening figure inside it.
 *
 * <p>In section {@code BALANCE}, which reaches no total, and correctly so: the
 * carried balance is already inside {@code netPayableAmount}. Adding the line as
 * well would count it twice.
 */
@Component
public class PreviousBalanceCalculator implements PayrollComponentCalculator {

    @Override
    public String calculationKey() {
        return CalculationKeys.PREVIOUS_BALANCE_CARRIED;
    }

    @Override
    public ComponentResult calculate(ComponentContext ctx) {
        BigDecimal previous = ctx.item().getPreviousNetPayableAmount() != null
                ? ctx.item().getPreviousNetPayableAmount() : BigDecimal.ZERO;

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("source", "payroll_run_items.previous_net_payable_amount");
        inputs.put("carriedFrom", ctx.periodStart().minusMonths(1).toString());

        return ComponentResult.amount(previous, inputs);
    }
}
