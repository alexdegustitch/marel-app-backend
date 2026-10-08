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

    private static final String DIRECTION_PLUS = "PLUS";
    private static final String DIRECTION_MINUS = "MINUS";

    /**
     * One line, reduced to what the formula actually reads: its section's
     * direction (PLUS, MINUS or NONE).
     *
     * <p>Lets the same arithmetic run over a REPLAYED handover, where there are
     * no entities left — only what the record kept. A record frozen before V63
     * kept the old vocabulary instead; {@link #legacyDirection} translates it.
     */
    public record Line(String direction, boolean applied, BigDecimal amount) {}

    /**
     * The old vocabulary, restated as a direction — for documents frozen before
     * sections carried one. GROSS_PLUS was the only impact the plus side ever
     * summed, and SETTLEMENTS the only section the minus side did; V63's guard
     * proved the two never met on one line, so the order here cannot matter.
     */
    public static String legacyDirection(String impactCode, String sectionCode) {
        if ("GROSS_PLUS".equals(impactCode)) {
            return DIRECTION_PLUS;
        }
        if ("SETTLEMENTS".equalsIgnoreCase(sectionCode)) {
            return DIRECTION_MINUS;
        }
        return "NONE";
    }

    public static PayrollTotals of(Collection<PayrollRunItemCategory> categories,
                                   Collection<PayrollAdjustment> adjustments,
                                   BigDecimal previousNetPayable) {

        BigDecimal categoriesSum = categories.stream()
                .map(c -> c.getAmount() != null ? c.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Line> lines = adjustments.stream()
                .map(a -> new Line(
                        a.getPayrollAdjustmentCategory().getSection().getDirection(),
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

        // One question per side, both answered by the section's direction. The
        // old formula asked two different vocabularies (impact on the plus side,
        // section on the minus side); V63 proved them impact-homogeneous per
        // section and refuses any database where they are not, so this is the
        // same arithmetic with the drift-prone half of the vocabulary gone.
        BigDecimal earningsSum = lines.stream()
                .filter(l -> l.applied() && DIRECTION_PLUS.equals(l.direction()))
                .map(l -> l.amount() != null ? l.amount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalNetEarnings = base.add(earningsSum).setScale(2, RoundingMode.HALF_UP);

        BigDecimal previouslyPaid = lines.stream()
                .filter(l -> l.applied() && DIRECTION_MINUS.equals(l.direction()))
                .map(l -> l.amount() != null ? l.amount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal currentBalance = totalNetEarnings.subtract(previouslyPaid).setScale(2, RoundingMode.HALF_UP);

        BigDecimal previous = previousNetPayable != null ? previousNetPayable : BigDecimal.ZERO;
        BigDecimal netPayable = previous.add(currentBalance).setScale(2, RoundingMode.HALF_UP);

        return new PayrollTotals(totalNetEarnings, previouslyPaid, currentBalance, netPayable);
    }
}
