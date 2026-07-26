package com.aleksandarparipovic.marel_app.user_session;

import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One login, tracked for presence.
 *
 * <p>Keyed on {@code family_id}, shared with {@code refresh_tokens.family_id}:
 * refresh tokens rotate on every refresh, so an individual token row is not a
 * stable identity for "this login" — the family is, because it is minted once at
 * login and carried through every rotation.
 *
 * <p>There is deliberately NO refresh_token_hash here. {@code refresh_tokens}
 * already owns the hashes; copying secret-derived material into a second table
 * would buy nothing. Raw tokens are never stored anywhere.
 */
@Entity
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "family_id", nullable = false, length = 64, updatable = false)
    private String familyId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /** Advanced by the heartbeat. Presence is derived from this, never from a flag. */
    @Column(name = "last_seen_at", nullable = false)
    @Builder.Default
    private OffsetDateTime lastSeenAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** The administrator, or the session's own user for a self-initiated logout. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    private User revokedBy;

    @Column(name = "logout_at")
    private OffsetDateTime logoutAt;

    @Column(name = "device_name", length = 150)
    private String deviceName;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
    }

    /**
     * Live means "could still be used". Being live is not the same as being online —
     * that additionally requires a recent heartbeat.
     */
    public boolean isLive() {
        return !isRevoked() && !isExpired();
    }

    public boolean isOnline(long onlineThresholdSeconds) {
        return isLive()
                && lastSeenAt != null
                && lastSeenAt.isAfter(OffsetDateTime.now().minusSeconds(onlineThresholdSeconds));
    }

    public void revoke(User actor) {
        if (revokedAt == null) {
            this.revokedAt = OffsetDateTime.now();
            this.revokedBy = actor;
        }
    }

    /** Self-initiated logout: recorded separately from an administrative revocation. */
    public void logout(User actor) {
        this.logoutAt = OffsetDateTime.now();
        revoke(actor);
    }
}
