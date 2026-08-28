package com.aleksandarparipovic.marel_app.customer.dto;

/**
 * One of a line item's notes, as the customer page shows it.
 *
 * <p>{@code matched} says this particular note is the one the search found, so
 * the page can point at it instead of leaving the reader to re-read the order
 * looking for their own words.
 */
public record CustomerOrderLineItemNoteRow(
        Long id,
        Integer orderNote,
        String note,
        boolean matched
) {
}
