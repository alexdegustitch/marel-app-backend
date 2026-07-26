package com.aleksandarparipovic.marel_app.production_order.dto;

import java.time.OffsetDateTime;

public record ProductionOrderLineItemNoteDto(
        Long id,
        Integer orderNote,
        String note,
        Boolean isActive,
        OffsetDateTime createdAt
) {
}
