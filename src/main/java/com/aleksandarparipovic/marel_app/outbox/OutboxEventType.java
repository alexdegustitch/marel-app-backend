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
    PRODUCTION_ORDER_COMPLETED,
    PRODUCTION_ORDER_DEADLINE_CHANGED
}
