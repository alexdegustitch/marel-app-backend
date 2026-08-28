package com.aleksandarparipovic.marel_app.outbox;

/**
 * Business events that are worth persisting and telling somebody about.
 *
 * <p>Transient UI feedback ("saved successfully") is NOT an event type here — it
 * never leaves the frontend. An event belongs on this list only if a user could
 * log out, come back, and still need to see it.
 */
public enum OutboxEventType {
    USER_REGISTRATION_REQUESTED,
    USER_REGISTRATION_APPROVED,
    USER_REGISTRATION_DECLINED,
    MANUFACTURING_TIME_REQUEST_CREATED,
    MANUFACTURING_TIME_REQUEST_ASSIGNED,
    MANUFACTURING_TIME_REQUEST_COMPLETED,
    MANUFACTURING_TIME_REQUEST_DECLINED,
    // Opens the order's e-mail conversation. Every later mail about the
    // order is a reply to the one this event sends.
    PRODUCTION_ORDER_CREATED,
    // Any edit to an order, with the list of what changed in the payload.
    // One event per SAVE, not per field: one save is one thing that
    // happened, and the recipients are reading a conversation.
    PRODUCTION_ORDER_UPDATED,
    PRODUCTION_ORDER_COMPLETED,
    /**
     * No longer published — PRODUCTION_ORDER_UPDATED covers a moved deadline
     * alongside everything else that changed in the same save. Kept because
     * rows written before that change still name it, and deserialising them
     * must not fail.
     */
    PRODUCTION_ORDER_DEADLINE_CHANGED
}
