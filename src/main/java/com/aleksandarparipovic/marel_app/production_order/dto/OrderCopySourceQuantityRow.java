package com.aleksandarparipovic.marel_app.production_order.dto;

import java.time.LocalDate;

/** One quantity row of a line item, with the date it was promised for. */
public record OrderCopySourceQuantityRow(
        Integer quantity,
        LocalDate deliveryDeadline
) {
}
