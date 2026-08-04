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
 * "Isplaćeno u prethodnom obračunskom periodu" — the sum of what has already been
 * settled: the instalment, last month's phone, and the two part-payments.
 *
 * <p>This is NOT a mirror of nothing, which is how it looked while it sat at zero.
 * It is the figure the closing balance is built from:
 *
 * <pre>
 *   PAID_PREVIOUS_PERIOD = INSTALLMENT + PHONE_PREVIOUS_MONTH + PAID_PART_1 + PAID_PART_2
 *   currentBalance       = totalNetEarnings − PAID_PREVIOUS_PERIOD
 * </pre>
 *
 * <p>Which is exactly what {@code PayrollRunItemService.recalculateSummaryTotals}
 * already computes as {@code previouslyPaidAmount}, by summing section
 * {@code SETTLEMENTS} — the same four lines. So this line SHOWS that total rather
 * than recomputing it, and the two cannot drift.
 *
 * <p><b>It must never be added to the settlements sum itself.</b> It sits in
 * section {@code SETTLEMENTS_SUM} — literally "the sum of the settlements" — and
 * counting it there would deduct everything twice. That is why the earnings side
 * moved to impact codes in phase 4 and this side did not.
 */
@Component
public class PaidPreviousPeriodCalculator implements PayrollComponentCalculator {

    @Override
    public String calculationKey() {
        return CalculationKeys.PAID_PREVIOUS_PERIOD_SUM;
    }

    @Override
    public ComponentResult calculate(ComponentContext ctx) {
        BigDecimal previouslyPaid = ctx.item().getPreviouslyPaidAmount() != null
                ? ctx.item().getPreviouslyPaidAmount() : BigDecimal.ZERO;

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("source", "payroll_run_items.previously_paid_amount");
        inputs.put("components", "INSTALLMENT + PHONE_PREVIOUS_MONTH + PAID_PART_1 + PAID_PART_2");

        return ComponentResult.amount(previouslyPaid, inputs);
    }
}
