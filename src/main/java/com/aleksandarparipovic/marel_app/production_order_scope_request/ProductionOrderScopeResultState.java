package com.aleksandarparipovic.marel_app.production_order_scope_request;

/**
 * How far the supervisor's answer has got.
 *
 * <p>Absent (null) means nothing has been saved yet. The two states below are
 * the difference between working and having handed the work over, which the
 * request's own status cannot express on its own — a saved-but-unsubmitted
 * answer leaves the request IN_REVIEW.
 */
public enum ProductionOrderScopeResultState {

    /** Saved and still the processor's to edit. */
    DRAFT,

    /**
     * Handed over. The request is COMPLETED and the answer is read-only for
     * everybody, the processor included — the database ties the two together in
     * {@code chk_po_scope_requests_result_state}.
     */
    SUBMITTED
}
