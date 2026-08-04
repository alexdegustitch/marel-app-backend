package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class AdjustmentPatchDto {

    /** ID of the PayrollAdjustment to update. Required. */
    private Long id;

    /** Override quantity — null means no change. */
    private BigDecimal quantity;

    /** Override unit amount — null means no change. */
    private BigDecimal unitAmount;

    /** Override final amount — null means no change. */
    private BigDecimal amount;

    /** Whether this adjustment is applied — null means no change. */
    private Boolean isApplied;

    /** Free-text note — null means no change. */
    private String note;

    /**
     * A delta to add on top of the system figure — the bonus correction.
     *
     * <p>Not an override: the system value stays visible and the two are summed.
     * Allowed only where {@code editableInput = CORRECTION}.
     */
    private BigDecimal correctionAmount;

    /**
     * The part of the line that is NOT the correction — for the monthly bonus,
     * the employee's own amount from their bonus category.
     *
     * <p>The line keeps `amount` as the effective total and `correction_amount`
     * as the tier, so the base is the difference and has no slot of its own.
     * Sending it as `amount` would be a typed total, and a typed total has no
     * parts — the next recalculation would collapse the split the panel shows.
     * This says "change the base, leave the correction where it is", and the
     * server adds them back together.
     *
     * <p>Not a second way to type a total: is_overridden stays off and no reason
     * is required, because the formula still runs.
     */
    private BigDecimal baseAmount;

    /**
     * Why the final amount was typed in, bypassing the formula.
     *
     * <p>Required whenever {@link #amount} sets a figure the calculation did not
     * produce. A hard override with no reason is exactly what D7 forbids: the audit
     * trail records who and when, but only this says what the decision was.
     */
    private String overrideReason;

    /** Drop a hard override and go back to the system figure. */
    private Boolean clearOverride;
}

