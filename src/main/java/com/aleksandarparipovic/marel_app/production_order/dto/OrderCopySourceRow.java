package com.aleksandarparipovic.marel_app.production_order.dto;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * One past order, offered as somewhere to copy line items FROM.
 *
 * <p>Everything here is context for a decision: is this the order I am thinking
 * of? So it carries the things people recognise an order by — who it was for,
 * who wrote it, when — rather than the things an order is processed by.
 *
 * <p>{@code matched} says the order's OWN fields are what the search found. Its
 * line items carry their own flag, because an order can be in the results
 * because of one line among ten and the reader has no way to tell which.
 */
public record OrderCopySourceRow(
        Long id,
        String code,
        String name,
        String note,
        ProductionOrderStatus status,
        LocalDate creationDate,
        LocalDate orderDate,
        Long customerId,
        String customerName,
        String customerCode,
        String customerTaxId,
        /** Who booked the order — the "ko je radio nalog" the filter narrows by. */
        Long userId,
        String userFullName,
        boolean matched,
        List<OrderCopySourceLineItemRow> lineItems
) {
}
