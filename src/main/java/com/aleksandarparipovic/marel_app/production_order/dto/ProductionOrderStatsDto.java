package com.aleksandarparipovic.marel_app.production_order.dto;

/**
 * The figures the order list shows above the table — a whole-population count,
 * not a count of the page in front of the reader.
 *
 * <p>The list itself is filtered, sorted and paged, so its rows answer "which
 * orders match what I asked for". These four counts answer a different question
 * — "what across every open order needs attention right now" — and so are
 * computed over all non-archived orders regardless of the current filter, the
 * same way the design mock derives them from the full set.
 *
 * <p><b>{@code withoutScope} is null-not-zero.</b> An order with no agreed
 * razrada has no denominator, so its progress is unknown rather than nought;
 * this count is how many open orders are in that state, which is a thing to act
 * on (somebody has to decide the scope) and not the same as "nothing done".
 *
 * @param total        every non-archived order, delivered or not
 * @param open         orders still in CREATED — "u toku"
 * @param delivered    orders already DELIVERED
 * @param late         open orders whose effective deadline is in the past
 * @param dueSoon      open orders due within three days, today included
 * @param withoutScope open orders with no agreed razrada (progress unknowable)
 */
public record ProductionOrderStatsDto(
        long total,
        long open,
        long delivered,
        long late,
        long dueSoon,
        long withoutScope
) {
}
