package com.aleksandarparipovic.marel_app.user_notification.dto;

import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;

import java.time.OffsetDateTime;

public record UserNotificationResponse(
        Long id,
        OutboxEventType type,
        String title,
        String message,
        /** Business entity this is about; following it still re-checks authorization. */
        String entityType,
        Long entityId,
        boolean read,
        boolean dismissed,
        OffsetDateTime createdAt
) {
}
