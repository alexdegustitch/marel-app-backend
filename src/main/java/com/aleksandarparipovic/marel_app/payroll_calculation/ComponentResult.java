package com.aleksandarparipovic.marel_app.payroll_calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * What the system computed for one line, before any human touches it.
 *
 * <p>{@code null} for {@link #systemQuantity} or {@link #systemUnitAmount} means
 * the line has no such component — a flat amount rather than count x price. It is
 * not zero.
 *
 * @param inputs what the calculator was given, and why it produced what it did.
 *               Stored on the adjustment so a zero on a payslip can be explained
 *               instead of guessed at.
 */
public record ComponentResult(
        BigDecimal systemQuantity,
        BigDecimal systemUnitAmount,
        BigDecimal systemAmount,
        Map<String, Object> inputs
) {
    private static final int AMOUNT_SCALE = 2;

    /** count x price, rounded once at the end. */
    public static ComponentResult quantityTimesUnit(BigDecimal quantity,
                                                    BigDecimal unitAmount,
                                                    Map<String, Object> inputs) {
        BigDecimal amount = quantity.multiply(unitAmount).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        return new ComponentResult(quantity, unitAmount, amount, inputs);
    }

    /** A flat amount with no count and no unit price. */
    public static ComponentResult amount(BigDecimal amount, Map<String, Object> inputs) {
        return new ComponentResult(null, null, amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP), inputs);
    }

    /**
     * Nothing to pay, WITH a reason.
     *
     * <p>The reason is not decoration: an unexplained zero on a payslip cannot be
     * told apart from a bug, and somebody has to be able to answer "why did this
     * person get no transport this month".
     */
    public static ComponentResult zero(String reason) {
        return new ComponentResult(BigDecimal.ZERO, null,
                BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
                Map.of("reason", reason));
    }

    /** The line keeps whatever it already had — a manual line. */
    /**
     * A figure the calculator put in {@link #inputs}, or ZERO when it recorded none.
     *
     * <p>For a component whose system value has NAMED PARTS the caller has to
     * store separately — the monthly bonus is the base from the employee's bonus
     * category plus the tier from the eligibility rules — and where widening this
     * record with bonus-specific fields would push one component's shape onto
     * every other calculator.
     */
    public BigDecimal numericInput(String key) {
        Object value = inputs == null ? null : inputs.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    public static ComponentResult unchanged() {
        return new ComponentResult(null, null, null, Map.of("reason", "MANUAL"));
    }

    public boolean isUnchanged() {
        return systemAmount == null;
    }
}
