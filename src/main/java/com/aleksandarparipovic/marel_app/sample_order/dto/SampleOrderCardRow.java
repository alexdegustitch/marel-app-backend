package com.aleksandarparipovic.marel_app.sample_order.dto;

import java.time.LocalDate;

/**
 * One sample order as the list shows it.
 *
 * <p>Thinner than a production order's card row by exactly the things a sample
 * order does not have: no flags, and one deadline instead of a list of delivery
 * windows. {@code lineItemCount} and {@code totalQuantity} are computed for the
 * whole page in two queries rather than per card — a list of twenty cards must
 * not be forty round trips.
 */
public record SampleOrderCardRow(
        Long id,
        String code,
        String name,
        String note,
        String status,
        LocalDate creationDate,
        LocalDate deadlineDate,
        String deadlineNote,
        /** Null when the samples are for nobody outside. */
        Long customerId,
        String customerName,
        Integer lineItemCount,
        Integer totalQuantity
) {
}
