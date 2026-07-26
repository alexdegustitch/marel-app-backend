package com.aleksandarparipovic.marel_app.outbox;

/**
 * Lifecycle of a transactional-outbox row.
 *
 * <pre>
 *   PENDING    -> PROCESSING
 *   PROCESSING -> PROCESSED
 *   PROCESSING -> FAILED
 *   FAILED     -> PROCESSING   (retry, once next_attempt_at is due)
 * </pre>
 *
 * FAILED is retryable until the configured maximum attempt count; after that the
 * row stays FAILED and stops being claimed, so it remains visible for operational
 * review rather than disappearing.
 */
public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED
}
