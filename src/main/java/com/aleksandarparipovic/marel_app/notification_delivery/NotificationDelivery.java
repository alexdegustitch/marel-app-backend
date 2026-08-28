package com.aleksandarparipovic.marel_app.notification_delivery;

import com.aleksandarparipovic.marel_app.notification_event.NotificationEvent;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One channel delivery attempt for a notification event, with its retry state.
 *
 * <p>Unique per (event, address) for EMAIL and per (event, user) for IN_APP, so
 * two workers replaying the same event can never send twice.
 */
@Entity
@Table(name = "notification_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_event_id", nullable = false, updatable = false)
    private NotificationEvent notificationEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private NotificationChannel channel;

    /** NULL for an external recipient with no application account. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id", updatable = false)
    private User recipientUser;

    /** The snapshot address actually used for this delivery. */
    @Column(name = "recipient_email", length = 320, updatable = false)
    private String recipientEmail;

    /**
     * Comma separated. Present exactly when this row is ONE message addressed to
     * several people, which is how an order's conversation stays a conversation:
     * everybody gets the same Message-ID, so a Reply All from any of them lands
     * in everybody's thread. NULL means the single-recipient path above.
     */
    @Column(name = "recipient_emails", updatable = false)
    private String recipientEmails;

    /**
     * Assigned when this row is CREATED, not when it is sent. A retry therefore
     * re-sends a byte-identical message, which every client discards as a
     * duplicate — so a send that succeeded but lost its acknowledgement cannot
     * put the same text in the thread twice.
     */
    @Column(name = "message_id", length = 255, updatable = false)
    private String messageId;

    @Column(name = "in_reply_to", length = 255, updatable = false)
    private String inReplyTo;

    /**
     * The conversation's subject as it stood when this mail was queued. Frozen
     * here rather than read from the thread at send time: clients weigh the
     * subject alongside References when grouping, so a subject that drifts
     * between messages splits the very thread the headers are holding together.
     */
    @Column(name = "thread_subject", length = 255, updatable = false)
    private String threadSubject;

    /** The thread's ancestor chain frozen as it was when this row was queued. */
    @Column(name = "references_header", updatable = false)
    private String referencesHeader;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private NotificationDeliveryStatus status = NotificationDeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /**
     * Sanitized and truncated by the worker. Must never carry passwords, tokens,
     * personal data or a full provider payload.
     */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false)
    private OffsetDateTime updatedAt;

    public void markSent() {
        this.status = NotificationDeliveryStatus.SENT;
        this.sentAt = OffsetDateTime.now();
        this.lastError = null;
    }

    public void markFailed(String sanitizedError, OffsetDateTime nextAttemptAt) {
        this.status = NotificationDeliveryStatus.FAILED;
        this.lastError = sanitizedError;
        this.nextAttemptAt = nextAttemptAt;
    }
}
