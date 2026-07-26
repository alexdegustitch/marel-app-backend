package com.aleksandarparipovic.marel_app.production_order_recipient.dto;

import com.aleksandarparipovic.marel_app.production_order_recipient.RecipientSourceType;

import java.time.OffsetDateTime;

public record ProductionOrderRecipientResponse(
        Long id,
        Long productionOrderId,
        Long userId,
        String recipientEmail,
        String recipientName,
        RecipientSourceType sourceType,
        Long sourceMailingListId,
        String sourceMailingListName,
        OffsetDateTime createdAt
) {
}
