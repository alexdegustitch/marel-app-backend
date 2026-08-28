package com.aleksandarparipovic.marel_app.customer.dto;

import java.util.List;

/**
 * One line of a production order, as the customer page shows it.
 *
 * <p>{@code matched} is true when the search found something on this line —
 * its own note, one of {@link #notes()}, or the product's name or code. That is
 * the whole point of carrying the lines here: the order alone cannot say WHICH
 * of its items the searched-for words are on.
 */
public record CustomerOrderLineItemRow(
        Long id,
        Integer lineOrder,
        Long productId,
        String productName,
        String productCode,
        String productDescription,
        Integer quantity,
        String note,
        boolean matched,
        List<CustomerOrderLineItemNoteRow> notes
) {
}
