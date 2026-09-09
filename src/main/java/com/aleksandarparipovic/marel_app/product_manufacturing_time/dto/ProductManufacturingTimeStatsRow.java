package com.aleksandarparipovic.marel_app.product_manufacturing_time.dto;

/**
 * The manufacturing-time board's KPI figures, one request for the whole page.
 *
 * <p>The two request counts obey the same visibility rule as the picker: a
 * caller without the read-all permission counts only their own requests, so a
 * tile never announces work its owner is not allowed to open.
 */
public record ProductManufacturingTimeStatsRow(
        /** The caller's own active records — what the personal list pages. */
        long mine,
        /** Active records that answer a request, whoever produced them. */
        long fromRequests,
        /** Requests nobody has taken yet — free to pick up. */
        long pendingRequests,
        /** Requests the caller has taken and not yet settled. */
        long myInReview
) {
}
