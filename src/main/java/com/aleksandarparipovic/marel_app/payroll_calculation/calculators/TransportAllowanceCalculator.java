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
 * <p><b>PER ARRIVAL.</b> Employees with {@code TRANSPORT_PER_DAY} in force are
 * paid for each time they CAME TO WORK, priced from the single company rate
 * {@code app_settings.transport_allowance_per_day}, in force when the payroll
 * month began.
 *
 * <p><b>An arrival is not a day and not a shift.</b> First shift followed
 * straight by the second is one journey, because nobody goes anywhere at the
 * changeover. First shift, home, then the third shift the same day is two.
 * Shifts less than {@link #ARRIVAL_GAP_MINUTES} minutes apart therefore chain
 * into one arrival; see {@code DailyReportRepository.countQualifyingArrivals}.
 *
 * <p>The code and the value are still called {@code TRANSPORT_PER_DAY} — renaming
 * a definition code would break every row that references it, and the code is an
 * identifier rather than a description. What it MEANS is per arrival.
 *
 * <p>THE ENTITLEMENT IS WHAT CHANGED, AND WHY (OPEN-15). This mode used to mean
 * "everyone without a fixed amount", which read nothing about the employee — so it
 * had no start date, and every month anybody had ever worked would be paid
 * transport the next time it was recalculated. 98 of 135 employees and 322 items
 * before 2026, none of them locked. Now the mode is a dated per-employee value,
 * exactly as the fixed mode already was.
 *
 * <p>An employee with NEITHER value is paid no transport. That is the point of the
 * change: before the entitlement's start date there is no transport rather than a
 * silent one.
 *
 * <p>The two are modes, not two rates for the same thing, which is why neither is
 * a "fallback" for the other: a fixed employee is not paid more for coming in more
 * often, and a per-day employee has no monthly figure to fall back to. The fixed
 * amount still wins where an employee somehow carries both, so a data mistake
 * cannot pay somebody twice.
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

    /**
     * How long a break between two shifts has to be before the second one counts
     * as a fresh journey to work. Sixty minutes: an employee who moves straight
     * from one shift into the next travelled once, and a shift entered with the
     * clock rounded a little should not become a second fare.
     *
     * <p>A constant rather than a setting because it is one company-wide rule and
     * nobody has asked to vary it. Moving it to {@code app_settings} later is a
     * one-line change here — the query already takes it as a parameter.
     */
    public static final int ARRIVAL_GAP_MINUTES = 60;

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
        if (ctx.hasFlag(EmployeePayrollValueCodes.TRANSPORT_PER_DAY)) {
            return perArrival(ctx);
        }
        // Neither mode is in force for this month. Not an error and not a zero
        // somebody chose — the employee simply has no transport entitlement on
        // this date, which is what every month before the entitlement starts
        // looks like.
        return ComponentResult.zero("NO_TRANSPORT_ENTITLEMENT");
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

    /** One fare per journey to work, at the one company rate. */
    private ComponentResult perArrival(ComponentContext ctx) {
        BigDecimal perArrival = ctx.settings().get(SETTING_TRANSPORT_PER_DAY);
        if (perArrival == null) {
            // Neither a personal monthly amount nor a company rate: nothing to pay,
            // and the payslip says which of the two is missing rather than showing
            // a bare zero.
            return ComponentResult.zero("NO_TRANSPORT_RATE_CONFIGURED");
        }

        long arrivals = dailyReportRepository.countQualifyingArrivals(
                ctx.employeeId(), ctx.periodStart(), ctx.periodEnd(), ARRIVAL_GAP_MINUTES);

        if (arrivals == 0) {
            return ComponentResult.zero("NO_DAYS_WORKED");
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("mode", "PER_ARRIVAL");
        inputs.put("arrivals", Math.toIntExact(arrivals));
        // Kept under its old name too: the payslip and any saved calculation_inputs
        // from before this change are read by the same screen, and a key that
        // disappears reads as a fault rather than a rename.
        inputs.put("workedDays", Math.toIntExact(arrivals));
        inputs.put("perDayAmount", perArrival);
        inputs.put("arrivalGapMinutes", ARRIVAL_GAP_MINUTES);
        inputs.put("rule", "one unit per arrival: worked shifts less than "
                + ARRIVAL_GAP_MINUTES + " minutes apart are one journey");
        inputs.put("periodStart", ctx.periodStart().toString());
        inputs.put("periodEnd", ctx.periodEnd().toString());

        return ComponentResult.quantityTimesUnit(BigDecimal.valueOf(arrivals), perArrival, inputs);
    }
}
