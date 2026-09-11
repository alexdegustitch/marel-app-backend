package com.aleksandarparipovic.marel_app.production_order.dto;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;

import java.time.LocalDate;

/**
 * One production order as a commercial colleague's PROFILE lists it — the orders
 * they wrote. Deliberately slim: enough to recognise the order and open it, no
 * line items, deadlines or progress. The order's own screen carries the rest.
 *
 * @param date the order date, or the creation date when the order carries none —
 *             the single date the profile row shows.
 */
public record UserOrderRow(
        Long id,
        String code,
        String name,
        /** Null when the order is internal (no customer). The id rides along so the profile row can link to the customer's page. */
        Long customerId,
        String customerName,
        LocalDate date,
        ProductionOrderStatus status
) {
}
