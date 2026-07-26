package com.aleksandarparipovic.marel_app.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A business event recorded in the SAME transaction as the change that caused it.
 *
 * <p>This is what makes notifications reliable without a message broker: if the
 * business transaction rolls back, the event vanishes with it; if it commits, the
 * event is durably queued even if the application dies one millisecond later.
 * {@code OutboxEventWorker} drains the table afterwards, outside the business
 * transaction, so no external call is ever made while a database transaction is
 * held open.
 *
 * <p>The polling + {@code FOR UPDATE SKIP LOCKED} approach mirrors the existing
 * {@code recalc_queue} workers rather than introducing Kafka or RabbitMQ for an
 * application of this size.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 60)
    private OutboxEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, length = 60)
    private OutboxAggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /**
     * Supplementary data for rendering the notification (names, codes). The
     * authoritative relationship is aggregateType + aggregateId, never this blob.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    /** Sanitized and truncated. Never a raw provider payload, token or personal data. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
