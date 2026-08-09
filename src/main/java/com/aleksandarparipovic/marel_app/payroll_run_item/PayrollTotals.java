package com.aleksandarparipovic.marel_app.payroll_run_item;

import com.aleksandarparipovic.marel_app.payroll_adjustment.PayrollAdjustment;
import com.aleksandarparipovic.marel_app.payroll_run_item_category.PayrollRunItemCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

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

    /**
     * One line, reduced to what the formula actually reads.
     *
     * <p>Lets the same arithmetic run over a REPLAYED handover, where there are
     * no entities left — only what the record kept.
     */
    public record Line(String impactCode, String sectionCode, boolean applied, BigDecimal amount) {}

    public static PayrollTotals of(Collection<PayrollRunItemCategory> categories,
                                   Collection<PayrollAdjustment> adjustments,
                                   BigDecimal previousNetPayable) {

        BigDecimal categoriesSum = categories.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Line> lines = adjustments.stream()
                .map(a -> new Line(
                        a.getPayrollAdjustmentCategory().getImpactCode(),
                        a.getPayrollAdjustmentCategory().getSectionCode(),
                        Boolean.TRUE.equals(a.getIsApplied()),
                        a.getAmount()))
                .toList();

        return ofValues(categoriesSum, lines, previousNetPayable);
    }

    /** The formula itself. Everything else here is an adapter onto it. */
    public static PayrollTotals ofValues(BigDecimal categoriesSum,
                                         Collection<Line> lines,
                                         BigDecimal previousNetPayable) {

        BigDecimal base = categoriesSum != null ? categoriesSum : BigDecimal.ZERO;

        // Summed by IMPACT, not by section, so a category moved between sections
        // for display cannot change what somebody is paid.
        BigDecimal earningsSum = lines.stream()
                .filter(l -> l.applied() && IMPACT_GROSS_PLUS.equals(l.impactCode()))
                .map(l -> l.amount() != null ? l.amount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalNetEarnings = base.add(earningsSum).setScale(2, RoundingMode.HALF_UP);

        // Still filtered by SECTION, deliberately: switching this side to impact
        // codes would pull in lines that reach no total today, and making one of
        // them start reducing somebody's pay is a business decision.
        BigDecimal previouslyPaid = lines.stream()
                .filter(l -> l.applied() && SECTION_SETTLEMENTS.equalsIgnoreCase(l.sectionCode()))
                .map(l -> l.amount() != null ? l.amount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal currentBalance = totalNetEarnings.subtract(previouslyPaid).setScale(2, RoundingMode.HALF_UP);

        BigDecimal previous = previousNetPayable != null ? previousNetPayable : BigDecimal.ZERO;
        BigDecimal netPayable = previous.add(currentBalance).setScale(2, RoundingMode.HALF_UP);

        return new PayrollTotals(totalNetEarnings, previouslyPaid, currentBalance, netPayable);
    }
}
