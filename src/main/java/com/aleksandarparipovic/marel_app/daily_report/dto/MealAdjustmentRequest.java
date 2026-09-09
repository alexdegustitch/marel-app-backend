package com.aleksandarparipovic.marel_app.daily_report.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A hand correction of one day's meal count.
 *
 * <p>The delta is added to the computed {@code meals_count}; sending 0 clears
 * the correction entirely (note and authorship included), so "undo" needs no
 * second endpoint. The bounds are sanity rails, not business rules — no shift
 * earns ten meals, so nobody should be able to type a hundred.
 */
public record MealAdjustmentRequest(
        @NotNull @Min(-10) @Max(10) Integer delta,
        @Size(max = 255) String note
) {
}
