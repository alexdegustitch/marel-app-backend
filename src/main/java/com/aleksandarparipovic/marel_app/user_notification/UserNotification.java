package com.aleksandarparipovic.marel_app.user_notification;

import com.aleksandarparipovic.marel_app.notification_event.NotificationEvent;
import com.aleksandarparipovic.marel_app.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One user's copy of a notification event, with their own read/dismiss state.
 *
 * <p>Unique per (event, user), so replaying the fan-out never produces a second
 * copy. Nothing here is ever deleted: dismissing hides, it does not remove.
 */
@Entity
@Table(name = "user_notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_event_id", nullable = false, updatable = false)
    private NotificationEvent notificationEvent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "dismissed_at")
    private OffsetDateTime dismissedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    /** Idempotent: marking an already-read notification read changes nothing. */
    public void markRead() {
        if (readAt == null) {
            readAt = OffsetDateTime.now();
        }
    }

    public void markUnread() {
        readAt = null;
    }

    /** Hides from the active list. Reading state is untouched. */
    public void dismiss() {
        if (dismissedAt == null) {
            dismissedAt = OffsetDateTime.now();
        }
    }

    public void restore() {
        dismissedAt = null;
    }
}
