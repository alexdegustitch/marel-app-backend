package com.aleksandarparipovic.marel_app.sample_order.dto;

import java.time.OffsetDateTime;

/**
 * One revision of a line's quantity.
 *
 * <p>A sample line has exactly one LIVE quantity; the rest are the numbers it
 * used to say. Carried to the client so the detail page can show that a quantity
 * was changed, and when — which is the whole reason the revisions are kept.
 */
public record SampleOrderLineItemQuantityDto(
        Long id,
        Integer orderQuantity,
        Integer quantity,
        Boolean isActive,
        OffsetDateTime createdAt
) {
}
