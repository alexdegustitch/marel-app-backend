package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * The one expression that turns lines into totals.
 *
 * <pre>
 *   totalNetEarnings     = SUM(categories) + SUM(applied GROSS_PLUS lines)
 *   previouslyPaidAmount = SUM(applied SETTLEMENTS lines)
 *   currentBalanceAmount = totalNetEarnings - previouslyPaidAmount
 *   netPayableAmount     = previousNetPayable + currentBalanceAmount
 * </pre>
 *
 * <p>Extracted so it can be evaluated over a SUBSET of the lines without being
 * written down twice. A reader who may not see one line gets the same
 * arithmetic with that term absent — if {@code a = X + Y - Z} and Y is not
 * theirs to see, they get {@code a = X - Z}. That is the existing rule applied
 * to fewer terms, not a second rule invented for them, and because both callers
 * evaluate THIS function the two can never drift apart.
 *
 * <p>Pure: no repositories, no entity writes. The caller decides which lines go
 * in, which is the whole point.
 */
public record PayrollTotals(
        BigDecimal totalNetEarnings,
        BigDecimal previouslyPaidAmount,
        BigDecimal currentBalanceAmount,
        BigDecimal netPayableAmount) {

    private static final String IMPACT_GROSS_PLUS = "GROSS_PLUS";
    private static final String SECTION_SETTLEMENTS = "SETTLEMENTS";

    public static PayrollTotals of(Collection<PayrollRunItemCategory> categories,
                                   Collection<PayrollAdjustment> adjustments,
                                   BigDecimal previousNetPayable) {

        BigDecimal categoriesSum = categories.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Summed by IMPACT, not by section, so a category moved between sections
        // for display cannot change what somebody is paid.
        BigDecimal earningsSum = adjustments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && IMPACT_GROSS_PLUS.equals(a.getPayrollAdjustmentCategory().getImpactCode()))
                .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalNetEarnings = categoriesSum.add(earningsSum).setScale(2, RoundingMode.HALF_UP);

        // Still filtered by SECTION, deliberately: switching this side to impact
        // codes would pull in lines that reach no total today, and making one of
        // them start reducing somebody's pay is a business decision.
        BigDecimal previouslyPaid = adjustments.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsApplied())
                        && SECTION_SETTLEMENTS.equalsIgnoreCase(
                                a.getPayrollAdjustmentCategory().getSectionCode()))
                .map(a -> a.getAmount() != null ? a.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal currentBalance = totalNetEarnings.subtract(previouslyPaid).setScale(2, RoundingMode.HALF_UP);

        BigDecimal previous = previousNetPayable != null ? previousNetPayable : BigDecimal.ZERO;
        BigDecimal netPayable = previous.add(currentBalance).setScale(2, RoundingMode.HALF_UP);

        return new PayrollTotals(totalNetEarnings, previouslyPaid, currentBalance, netPayable);
    }
}
