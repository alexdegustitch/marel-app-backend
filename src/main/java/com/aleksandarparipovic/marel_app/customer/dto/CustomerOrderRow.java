package com.aleksandarparipovic.marel_app.customer.dto;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * One production order made for this customer, as their page needs it.
 *
 * <p>Not the order's full card — the order page remains the place for that —
 * but enough to recognise it, to read the notes that were searched for, and to
 * open it.
 *
 * <p><b>The match flags.</b> When a search is running, {@code matched} says the
 * order's OWN fields — its code, name or note — are what matched, and each line
 * item carries its own flag. An order can be in the result because of a line
 * item nobody would spot by eye, so the page is told which one rather than
 * asked to guess.
 */
public record CustomerOrderRow(
        Long id,
        String code,
        String name,
        String note,
        ProductionOrderStatus status,
        LocalDate creationDate,
        LocalDate orderDate,
        String deliveryDeadline,
        Boolean testingRequired,
        Boolean isHighPriority,
        Boolean isAnnounced,
        Boolean hasSuccessiveDeliveries,
        /** The order's own code, name or note matched. False when nothing is searched for. */
        boolean matched,
        /** How many of {@link #lineItems()} matched; 0 when nothing is searched for. */
        int matchedLineItemCount,
        List<CustomerOrderLineItemRow> lineItems
) {
}
