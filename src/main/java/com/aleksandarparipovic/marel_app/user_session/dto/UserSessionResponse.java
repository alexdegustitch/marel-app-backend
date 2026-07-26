package com.aleksandarparipovic.marel_app.user_session.dto;

import java.time.OffsetDateTime;

/**
 * Session detail safe to return.
 *
 * <p>Carries no token hash and no family id — a client has no use for either, and
 * exposing them would leak security internals for no benefit.
 */
public record UserSessionResponse(
        Long id,
        String deviceName,
        String userAgent,
        String ipAddress,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime expiresAt,
        boolean online,
        boolean current
) {
}
