package com.aleksandarparipovic.marel_app.payroll_calculation.calculators;

import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRule;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRuleRepository;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleRepository;
import com.aleksandarparipovic.marel_app.employee_bonus.EmployeeBonusRepository;
import com.aleksandarparipovic.marel_app.payroll_calculation.CalculationKeys;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentContext;
import com.aleksandarparipovic.marel_app.payroll_calculation.ComponentResult;
import com.aleksandarparipovic.marel_app.payroll_calculation.PayrollComponentCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The monthly bonus: a personal base, earned or not, plus an hours tier.
 *
 * <p>Two independent parts, which is why one number could never express it:
 *
 * <ol>
 *   <li><b>The base.</b> Each employee belongs to a bonus category
 *       ({@code employees_bonus_history} → {@code bonus_categories}), and that
 *       category carries an amount. It is paid ONLY if the employee worked at
 *       least {@code bonus_min_hours_rules.min_num_hours} for the month. Below
 *       that threshold the base is nothing at all — not a proportion of it.</li>
 *   <li><b>The tier.</b> {@code bonus_eligibility_rules} holds several thresholds
 *       per month; the highest one the employee reached adds its
 *       {@code bonus_value} on top.</li>
 * </ol>
 *
 * <p>Both parts are month-specific: the rules are keyed by {@code period}, so a
 * month with no rule row pays no bonus rather than falling back to another
 * month's numbers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonthlyBonusCalculator implements PayrollComponentCalculator {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final EmployeeBonusRepository employeeBonusRepository;
    private final BonusMinHoursRuleRepository minHoursRuleRepository;
    private final BonusEligibilityRuleRepository eligibilityRuleRepository;

    /** The employee's own bonus-category amount, granted whole or not at all. */
    public static final String INPUT_BASE_BONUS = "baseBonus";
    /** The hours tier from bonus_eligibility_rules — what the payslip calls the correction. */
    public static final String INPUT_TIER_BONUS = "tierBonus";

    @Override
    public String calculationKey() {
        return CalculationKeys.MONTHLY_BONUS_FROM_RULES;
    }

    @Override
    public ComponentResult calculate(ComponentContext ctx) {
        if (ctx.employeeId() == null) {
            return ComponentResult.zero("NO_EMPLOYEE");
        }

        // "How many hours did they work" — PAYABLE minutes: the worked minutes plus
        // whatever was corrected by hand. Not shift duration and not paid absence.
        //
        // It used to read total_work_minutes, which ignored the corrections. An
        // administrator who added a forgotten shift saw the hours go up on screen
        // and the bonus stay where it was, because the threshold was still being
        // measured against the uncorrected figure. Whether somebody earned the
        // bonus has to be decided on the hours they are actually paid for.
        //
        // PayrollRunItemService sets total_payroll_minutes before it reaches this
        // calculator, so the value here is the one this recalculation produced.
        int payableMinutes = ctx.item().getTotalPayrollMinutes() != null
                ? ctx.item().getTotalPayrollMinutes()
                : (ctx.item().getTotalWorkMinutes() != null ? ctx.item().getTotalWorkMinutes() : 0);
        BigDecimal hoursWorked = BigDecimal.valueOf(payableMinutes)
                .divide(MINUTES_PER_HOUR, 4, RoundingMode.HALF_UP);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("hoursWorked", hoursWorked);
        inputs.put("hoursSource", "total_payroll_minutes");
        inputs.put("period", ctx.periodStart().toString());

        BigDecimal base = baseBonus(ctx, hoursWorked, inputs);
        BigDecimal tier = tierBonus(ctx, hoursWorked, inputs);

        BigDecimal total = base.add(tier).setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            inputs.put("reason", "NO_BONUS_EARNED");
        }
        return ComponentResult.amount(total, inputs);
    }

    /**
     * The employee's own category amount, all of it or none of it.
     *
     * <p>A threshold, not a proportion: somebody an hour short of the minimum earns
     * nothing rather than almost all of it.
     */
    private BigDecimal baseBonus(ComponentContext ctx, BigDecimal hoursWorked,
                                 Map<String, Object> inputs) {
        var minHoursRule = minHoursRuleRepository
                .findByPeriodAndArchivedAtIsNull(ctx.periodStart())
                .orElse(null);
        if (minHoursRule == null) {
            // No rule for the month means the base was never configured for it.
            // Paying it anyway would apply a threshold nobody set.
            inputs.put("baseBonusReason", "NO_MIN_HOURS_RULE_FOR_PERIOD");
            return BigDecimal.ZERO;
        }
        inputs.put("minHoursRequired", minHoursRule.getMinNumHours());

        if (hoursWorked.compareTo(BigDecimal.valueOf(minHoursRule.getMinNumHours())) < 0) {
            inputs.put("baseBonusReason", "BELOW_MINIMUM_HOURS");
            return BigDecimal.ZERO;
        }

        var employeeBonus = employeeBonusRepository
                .findInForce(ctx.employeeId(), ctx.periodStart())
                .orElse(null);
        if (employeeBonus == null || employeeBonus.getBonusCategory() == null) {
            inputs.put("baseBonusReason", "NO_BONUS_CATEGORY_FOR_EMPLOYEE");
            return BigDecimal.ZERO;
        }

        BigDecimal amount = employeeBonus.getBonusCategory().getBonusAmount();
        inputs.put("bonusCategory", employeeBonus.getBonusCategory().getCategoryNo());
        inputs.put(INPUT_BASE_BONUS, amount);
        return amount != null ? amount : BigDecimal.ZERO;
    }

    /** The highest hours threshold the employee reached this month. */
    private BigDecimal tierBonus(ComponentContext ctx, BigDecimal hoursWorked,
                                 Map<String, Object> inputs) {
        List<BonusEligibilityRule> rules = eligibilityRuleRepository
                .findByPeriodAndArchivedAtIsNullOrderByMinNumHoursAsc(ctx.periodStart());

        // saturday_count is the TIER ORDINAL, not a second dimension to match on.
        // BonusCalendarSyncService derives each tier from the work calendar:
        // min_num_hours = (workdays + ordinal) * 8, and is_active = false where the
        // month does not actually have that many working Saturdays. So the rules
        // already are the ladder, and the only thing left to do is find the highest
        // rung this employee reached.
        //
        // is_active carries the whole Saturday question. Filtering on it is what
        // stops a five-Saturday tier paying out in a month with four.
        List<BonusEligibilityRule> reached = rules.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .filter(r -> r.getMinNumHours() != null)
                .filter(r -> hoursWorked.compareTo(BigDecimal.valueOf(r.getMinNumHours())) >= 0)
                .toList();

        BonusEligibilityRule best = reached.stream()
                .max(Comparator.comparingInt(BonusEligibilityRule::getMinNumHours))
                .orElse(null);

        if (best == null || best.getBonusValue() == null) {
            inputs.put("tierBonusReason", rules.isEmpty()
                    ? "NO_ELIGIBILITY_RULES_FOR_PERIOD" : "NO_TIER_REACHED");
            return BigDecimal.ZERO;
        }

        inputs.put("tierMinHours", best.getMinNumHours());
        inputs.put("tierSaturdayOrdinal", best.getSaturdayCount());
        inputs.put(INPUT_TIER_BONUS, best.getBonusValue());
        return best.getBonusValue();
    }
}
