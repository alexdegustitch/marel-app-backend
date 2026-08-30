package com.aleksandarparipovic.marel_app.outbox;

/**
 * The business entity an outbox event is about. Kept as an enum rather than a
 * free string so that entity_type values stay a closed, greppable set — the
 * notification API resolves authorization from this pair.
 */
public enum OutboxAggregateType {
    USER_REGISTRATION_REQUEST,
    MANUFACTURING_TIME_REQUEST,
    ORDER_SCOPE_REQUEST,
    PRODUCTION_ORDER,
    SAMPLE_ORDER,
    PAYROLL_CHANGE_REQUEST
}
