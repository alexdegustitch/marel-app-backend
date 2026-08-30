package com.aleksandarparipovic.marel_app.production_order_scope_request;

import java.util.Set;

/**
 * Lifecycle of a request for an order's scope.
 *
 * <pre>
 *   PENDING   -> IN_REVIEW     (assign / claim)
 *   PENDING   -> CANCELLED     (requester withdraws)
 *   IN_REVIEW -> COMPLETED     (the answer is submitted)
 *   IN_REVIEW -> DECLINED
 *   IN_REVIEW -> CANCELLED
 * </pre>
 *
 * <p>Deliberately identical to {@code ManufacturingTimeRequestStatus}: the two
 * workflows are the same workflow over different subject matter, and a reader
 * who has understood one must not have to re-learn the other. PENDING is never
 * decided directly, so {@code assigned_to} always names whoever took
 * responsibility for the outcome.
 *
 * <p>COMPLETED, DECLINED and CANCELLED are terminal.
 */
public enum ProductionOrderScopeRequestStatus {

    PENDING,
    IN_REVIEW,
    COMPLETED,
    DECLINED,
    CANCELLED;

    private static final Set<ProductionOrderScopeRequestStatus> FROM_PENDING =
            Set.of(IN_REVIEW, CANCELLED);

    private static final Set<ProductionOrderScopeRequestStatus> FROM_IN_REVIEW =
            Set.of(COMPLETED, DECLINED, CANCELLED);

    /** Still moving: may be assigned, processed or withdrawn. */
    public boolean isOpen() {
        return this == PENDING || this == IN_REVIEW;
    }

    public boolean canTransitionTo(ProductionOrderScopeRequestStatus target) {
        return switch (this) {
            case PENDING -> FROM_PENDING.contains(target);
            case IN_REVIEW -> FROM_IN_REVIEW.contains(target);
            default -> false;
        };
    }
}
