package com.aleksandarparipovic.marel_app.production_order_scope_request;

/**
 * What was asked for: the whole order, or one line of it.
 *
 * <p>Both are stored the same way — as a list of items — so the workflow has one
 * shape and the completion modal one behaviour. This records the QUESTION, which
 * the item count cannot: an order with a single line makes "all of it" and "this
 * one line" indistinguishable by counting, and they are not the same request.
 */
public enum ProductionOrderScopeRequestScope {

    /** Every active line of the order at the moment the request was raised. */
    ORDER,

    /** One named line. */
    LINE_ITEM
}
