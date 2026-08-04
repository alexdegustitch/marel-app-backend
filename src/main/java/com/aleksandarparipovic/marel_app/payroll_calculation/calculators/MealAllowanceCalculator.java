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
 * Meals counted by the daily recalculation, priced from {@code app_settings}.
 *
 * <p>The COUNT is not recomputed here. {@code DailyRecalcService} already derives
 * it per day as {@code (eligibleMinutes + 240) / 480} and
 * {@code MonthlyRecalcService} sums it into {@code meal_allowance_num}. Deriving
 * it a second time would give the payroll layer its own opinion about how many
 * meals somebody had, and the two would drift.
 */
@Component
public class MealAllowanceCalculator implements PayrollComponentCalculator {

    public static final String SETTING_MEAL_PER_DAY = "meal_allowance_per_day";

    @Override
    public String calculationKey() {
        return CalculationKeys.MEAL_BY_ELIGIBLE_SHIFTS;
    }

    @Override
    public ComponentResult calculate(ComponentContext ctx) {
        BigDecimal unitAmount = ctx.settings().get(SETTING_MEAL_PER_DAY);
        if (unitAmount == null) {
            // A rate that has not been configured is not a rate of zero. Saying so
            // is what lets somebody find out why a payslip lost its meal line.
            return ComponentResult.zero("NO_MEAL_RATE_CONFIGURED");
        }

        // A count of zero still reports the PRICE. The unit amount is shown on the
        // payslip and is the field an administrator may edit, so a month with no
        // eligible meals must not blank it out and make the line look unconfigured.
        Integer count = ctx.monthlyReport().getMealAllowanceNum();
        if (count == null || count < 0) {
            count = 0;
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("mealCount", count);
        inputs.put("unitAmount", unitAmount);
        inputs.put("source", "monthly_reports.meal_allowance_num");
        inputs.put("pricedOn", ctx.periodStart().toString());

        return ComponentResult.quantityTimesUnit(BigDecimal.valueOf(count), unitAmount, inputs);
    }
}
