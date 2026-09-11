package com.aleksandarparipovic.marel_app.customer.dto;

/**
 * One customer's order figures, for the KPI row on their page. Live
 * (non-archived) orders only, matching what the two tables under the row show.
 */
public record CustomerDetailStatsDto(
        long productionOrders,
        /** Still in CREATED — the work currently on the books. */
        long activeProductionOrders,
        long deliveredProductionOrders,
        long sampleOrders,
        /** Sample orders not yet closed. */
        long openSampleOrders
) {
}
