package com.aleksandarparipovic.marel_app.sample_order.dto;

import java.time.OffsetDateTime;

/**
 * One revision of a line's note.
 *
 * <p>{@code orderNote} is served from the column the database actually calls
 * {@code order_quantity} — a naming slip in the original schema that the entity
 * maps as-is. Renamed here rather than in the table, because the column is the
 * one thing that cannot be changed without a rewrite nobody has asked for.
 */
public record SampleOrderLineItemNoteDto(
        Long id,
        Integer orderNote,
        String note,
        Boolean isActive,
        OffsetDateTime createdAt
) {
}
