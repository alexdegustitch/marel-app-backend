package com.aleksandarparipovic.marel_app.notification_event;

import com.aleksandarparipovic.marel_app.outbox.OutboxEvent;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One persistent business event — the thing that happened, stored once.
 *
 * <p>Who sees it lives in user_notifications; how it goes out lives in
 * notification_deliveries. Keeping those apart is what lets one event reach many
 * users without duplicating its text, and what makes fan-out safe to replay.
 */
@Entity
@Table(name = "notification_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The outbox row this came from. UNIQUE in the database, which is the backbone
     * of outbox idempotency: a retried outbox row cannot create a second event.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "outbox_event_id", updatable = false)
    private OutboxEvent outboxEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 60)
    private OutboxEventType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actorUser;

    /** Authoritative entity reference. The payload is never a substitute for this. */
    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;
}
