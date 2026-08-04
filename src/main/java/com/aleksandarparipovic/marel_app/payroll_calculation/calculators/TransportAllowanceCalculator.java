package com.aleksandarparipovic.marel_app.payroll_calculation.calculators;

import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueCodes;
import com.aleksandarparipovic.marel_app.payroll_calculation.CalculationKeys;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentContext;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentResult;
import com.aleksandarparipovic.marel_app.payroll_calculation.PayrollComponentCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transport, paid one of two ways.
 *
 * <p><b>FIXED.</b> Some employees have a fixed MONTHLY amount. They are paid it
 * whole — it does not depend on how much they worked, so no shift is counted at
 * all. Having a {@code TRANSPORT_FIXED_MONTHLY} value in force is what puts an
 * employee on this mode; there is no separate flag to fall out of step with it.
 *
 * <p><b>PER DAY.</b> Everyone else is paid for the days they actually worked,
 * priced from the single company rate {@code app_settings.transport_allowance_per_day}.
 *
 * <p>The two are modes, not two rates for the same thing, which is why neither is
 * a "fallback" for the other: a fixed employee is not paid more for coming in more
 * often, and a per-day employee has no monthly figure to fall back to.
 *
 * <p>What counts as a day worked is a {@code daily_reports} row with
 * {@code total_work_minutes > 0}. That excludes absence and sick leave — those are
 * category types the daily recalculation keeps out of work minutes — and it is not
 * the planned shift duration. See D3.
 */
@Component
@RequiredArgsConstructor
public class TransportAllowanceCalculator implements PayrollComponentCalculator {

    public static final String SETTING_TRANSPORT_PER_DAY = "transport_allowance_per_day";

    private final DailyReportRepository dailyReportRepository;

    @Override
    public String calculationKey() {
        return CalculationKeys.TRANSPORT_BY_QUALIFYING_SHIFTS;
    }

    @Override
    public ComponentResult calculate(ComponentContext ctx) {
        if (ctx.employeeId() == null) {
            return ComponentResult.zero("NO_EMPLOYEE");
        }

        BigDecimal fixedMonthly =
                ctx.employeeValues().get(EmployeePayrollValueCodes.TRANSPORT_FIXED_MONTHLY);
        if (fixedMonthly != null) {
            return fixedMonthlyAmount(fixedMonthly);
        }
        return perWorkedDay(ctx);
    }

    /**
     * The whole monthly amount, regardless of attendance.
     *
     * <p>Quantity 1 rather than a day count: the figure is not a price per anything,
     * and showing it as "22 x something" on a payslip would be a fiction.
     */
    private ComponentResult fixedMonthlyAmount(BigDecimal fixedMonthly) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("mode", "FIXED_MONTHLY");
        inputs.put("monthlyAmount", fixedMonthly);
        inputs.put("rule", "paid whole; attendance does not change it");

        return ComponentResult.quantityTimesUnit(BigDecimal.ONE, fixedMonthly, inputs);
    }

    /** Days actually worked, at the one company rate. */
    private ComponentResult perWorkedDay(ComponentContext ctx) {
        BigDecimal perDay = ctx.settings().get(SETTING_TRANSPORT_PER_DAY);
        if (perDay == null) {
            // Neither a personal monthly amount nor a company rate: nothing to pay,
            // and the payslip says which of the two is missing rather than showing
            // a bare zero.
            return ComponentResult.zero("NO_TRANSPORT_RATE_CONFIGURED");
        }

        long workedDays = dailyReportRepository.countQualifyingShifts(
                ctx.employeeId(), ctx.periodStart(), ctx.periodEnd());

        if (workedDays == 0) {
            return ComponentResult.zero("NO_DAYS_WORKED");
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("mode", "PER_WORKED_DAY");
        inputs.put("workedDays", Math.toIntExact(workedDays));
        inputs.put("perDayAmount", perDay);
        inputs.put("rule", "one unit per work_shift record with total_work_minutes > 0");
        inputs.put("periodStart", ctx.periodStart().toString());
        inputs.put("periodEnd", ctx.periodEnd().toString());

        return ComponentResult.quantityTimesUnit(BigDecimal.valueOf(workedDays), perDay, inputs);
    }
}
