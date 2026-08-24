package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A change of sign-in address, in progress.
 *
 * <p>The address on {@code users} does not move until the person proves they can
 * read the NEW mailbox. Until then the old one still signs in and still receives
 * everything — which is the whole safety property: somebody who gets hold of a
 * live session for a minute cannot walk off with the account by pointing it at
 * their own address.
 *
 * <p><b>The code is stored hashed.</b> Same encoder as a password, for the same
 * reason: a leaked backup of this table must not hand anybody a live code.
 */
@Entity
@Table(name = "email_change_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "new_email", nullable = false, length = 255, updatable = false)
    private String newEmail;

    @Column(name = "code_hash", nullable = false, length = 255, updatable = false)
    private String codeHash;

    /**
     * Wrong codes tried against this request.
     *
     * <p>Six digits is a million possibilities, which is nothing to a machine and
     * a lot to a person. This counter is what makes it one guess at a time: past
     * {@link EmailChangeService#MAX_ATTEMPTS} the request is dead and a new one
     * has to be started, which needs the password again and sends a new code.
     */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "requested_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    /** Neither confirmed nor cancelled nor timed out — the one the code belongs to. */
    public boolean isLive(OffsetDateTime now) {
        return confirmedAt == null && cancelledAt == null && expiresAt.isAfter(now);
    }

    public boolean isExpired(OffsetDateTime now) {
        return confirmedAt == null && cancelledAt == null && !expiresAt.isAfter(now);
    }
}
