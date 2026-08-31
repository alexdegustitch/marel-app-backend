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
    // The order-scope workflow: which operations an order actually needs.
    // Same four moments as above, because it is the same workflow over
    // different subject matter.
    ORDER_SCOPE_REQUEST_CREATED,
    ORDER_SCOPE_REQUEST_ASSIGNED,
    ORDER_SCOPE_REQUEST_COMPLETED,
    ORDER_SCOPE_REQUEST_DECLINED,
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
    PRODUCTION_ORDER_DEADLINE_CHANGED,
    /**
     * The same three moments for a nalog za izradu uzoraka. Its own values
     * rather than a reuse of the production-order ones: the two are different
     * documents with different words in the mail, and a shared type would make
     * "which order is this about" a question with two tables to look in.
     */
    SAMPLE_ORDER_CREATED,
    SAMPLE_ORDER_UPDATED,
    SAMPLE_ORDER_COMPLETED,
    /**
     * A supervisor asking payroll to reopen a month, and payroll's answer.
     *
     * <p>In-app only, like the other request workflows: these reach colleagues
     * who are signed in and working, and the answer is on a screen they already
     * have open.
     */
    PAYROLL_CHANGE_REQUEST_CREATED,
    PAYROLL_CHANGE_REQUEST_ACCEPTED,
    PAYROLL_CHANGE_REQUEST_DECLINED
}
