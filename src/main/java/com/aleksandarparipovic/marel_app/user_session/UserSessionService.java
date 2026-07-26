package com.aleksandarparipovic.marel_app.user_session;

import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_session.dto.UserSessionResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Sessions and online presence.
 *
 * <p>Presence is <b>derived</b>: a user is online when at least one session is
 * unrevoked, unexpired, and was seen within the configured threshold. There is no
 * {@code users.is_online} flag, because a crashed client or killed process would
 * strand it as a permanent lie.
 *
 * <p>An ACTIVE account and an online user are different things: the first says the
 * person is allowed in, the second says they are here now.
 */
@Service
@RequiredArgsConstructor
public class UserSessionService {

    private final UserSessionRepository sessionRepository;
    private final UserRepository userRepository;

    /**
     * How recently a session must have reported in to count as online. Configuration
     * rather than a constant, because the right value depends on the client's
     * heartbeat interval and both must be tuned together.
     */
    @Value("${app.session.online-threshold-seconds:120}")
    private long onlineThresholdSeconds;

    @Value("${app.session.heartbeat-seconds:45}")
    private long heartbeatSeconds;

    /**
     * Records a login. Keyed by family id, so a retry of the same login updates
     * rather than duplicating.
     */
    @Transactional
    public UserSession createForLogin(
            User user, String familyId, OffsetDateTime expiresAt,
            String ipAddress, String userAgent
    ) {
        return sessionRepository.findByFamilyId(familyId)
                .map(existing -> {
                    existing.setLastSeenAt(OffsetDateTime.now());
                    existing.setExpiresAt(expiresAt);
                    return existing;
                })
                .orElseGet(() -> sessionRepository.save(UserSession.builder()
                        .user(user)
                        .familyId(familyId)
                        .expiresAt(expiresAt)
                        .ipAddress(trim(ipAddress, 100))
                        .userAgent(trim(userAgent, 500))
                        .deviceName(deviceNameFrom(userAgent))
                        .build()));
    }

    /**
     * Advances last_seen_at for the caller's own session.
     *
     * <p>The family id comes from the caller's refresh-token family, never from the
     * request body — a client that could name an arbitrary session (or user) could
     * forge anyone's presence.
     */
    @Transactional
    public void heartbeat(String familyId) {
        sessionRepository.touchLastSeen(familyId, OffsetDateTime.now());
    }

    @Transactional
    public void logout(String familyId, Long actorId) {
        sessionRepository.findByFamilyId(familyId)
                .ifPresent(session -> session.logout(loadUser(actorId)));
    }

    /**
     * Administrative revocation. Callers must hold USER_SESSION_REVOKE, enforced at
     * the controller; ownership is re-checked here so a user can always end their
     * own session without that permission.
     */
    @Transactional
    public void revoke(Long sessionId, Long actorId, boolean actorMayRevokeAny) {
        UserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Sesija nije pronađena: " + sessionId));

        boolean isOwnSession = session.getUser().getId().equals(actorId);
        if (!isOwnSession && !actorMayRevokeAny) {
            throw new AccessDeniedException("Nemate ovlašćenje da prekinete ovu sesiju.");
        }

        session.revoke(loadUser(actorId));
    }

    @Transactional(readOnly = true)
    public List<UserSessionResponse> listForUser(Long userId, String currentFamilyId) {
        return sessionRepository.findLiveByUserId(userId).stream()
                .map(session -> toResponse(session, currentFamilyId))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isOnline(Long userId) {
        return sessionRepository.findLiveByUserId(userId).stream()
                .anyMatch(session -> session.isOnline(onlineThresholdSeconds));
    }

    /** Bulk presence, for lists that show who is currently online. */
    @Transactional(readOnly = true)
    public List<Long> onlineUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now();
        return sessionRepository.findOnlineUserIds(
                userIds, now, now.minusSeconds(onlineThresholdSeconds));
    }

    public long getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    /** Best-effort friendly label; the full user agent is kept separately. */
    private static String deviceNameFrom(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("electron")) return "Marel Desktop";
        if (ua.contains("edg/")) return "Edge";
        if (ua.contains("chrome")) return "Chrome";
        if (ua.contains("firefox")) return "Firefox";
        if (ua.contains("safari")) return "Safari";
        return null;
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private UserSessionResponse toResponse(UserSession session, String currentFamilyId) {
        return new UserSessionResponse(
                session.getId(),
                session.getDeviceName(),
                session.getUserAgent(),
                session.getIpAddress(),
                session.getCreatedAt(),
                session.getLastSeenAt(),
                session.getExpiresAt(),
                session.isOnline(onlineThresholdSeconds),
                session.getFamilyId().equals(currentFamilyId)
        );
    }
}
