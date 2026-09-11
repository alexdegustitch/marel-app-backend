package com.aleksandarparipovic.marel_app.customer.dto;

/**
 * The customer board's figures, answered in one request. Counted over the whole
 * customer list, deliberately ignoring whatever the grid is currently filtered
 * to — the tiles say what the list of customers IS, and clicking one narrows
 * the grid to it (the convention set by the product and operation boards).
 */
public record CustomerStatsDto(
        long total,
        long active,
        long archived,
        /** Active customers with at least one live production order still in CREATED. */
        long withActiveOrders
) {
}
