package com.aleksandarparipovic.marel_app.production_order.dto;

/**
 * The three figures a commercial colleague's profile shows above their orders:
 * how many they have written in all, how many this month, and how many are still
 * open. Counted over their non-archived orders, not the page in front of the
 * reader.
 *
 * @param total     every non-archived order this user wrote
 * @param thisMonth those entered since the first of the current month
 * @param active    those still in CREATED — "u radu", not yet delivered
 */
public record UserOrderStatsDto(
        long total,
        long thisMonth,
        long active
) {
}
