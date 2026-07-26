package com.aleksandarparipovic.marel_app.notification_delivery;

/**
 * <pre>
 *   PENDING    -> PROCESSING
 *   PROCESSING -> SENT
 *   PROCESSING -> FAILED
 *   FAILED     -> PROCESSING   (retry once next_attempt_at is due)
 *   PENDING    -> CANCELLED
 *   FAILED     -> CANCELLED
 * </pre>
 *
 * A row that exhausts its retries stays FAILED and stops being claimed, so it
 * remains visible for operational review instead of disappearing.
 */
public enum NotificationDeliveryStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    CANCELLED
}
