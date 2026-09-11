package com.aleksandarparipovic.marel_app.customer.dto;

import java.time.LocalDate;

/**
 * One sample order made for this customer, as their page lists it.
 *
 * <p>Slimmer than {@link CustomerOrderRow} on purpose: the sample table on the
 * customer's page identifies the order and its rok, and the order's own page
 * remains the place for the lines. {@code status} is the free-text column —
 * {@code created} / {@code closed} — exactly as the sample screens read it.
 */
public record CustomerSampleOrderRow(
        Long id,
        String code,
        String name,
        String note,
        String status,
        LocalDate creationDate,
        LocalDate deadlineDate,
        String deadlineNote
) {
}
