package com.aleksandarparipovic.marel_app.manufacturing_time_request;

import java.util.Set;

/**
 * Lifecycle of a manufacturing-time request.
 *
 * <pre>
 *   PENDING   -> IN_REVIEW     (assign / claim)
 *   PENDING   -> CANCELLED     (requester withdraws)
 *   IN_REVIEW -> COMPLETED
 *   IN_REVIEW -> DECLINED
 *   IN_REVIEW -> CANCELLED
 * </pre>
 *
 * <p><b>PENDING -> COMPLETED and PENDING -> DECLINED are deliberately NOT
 * allowed.</b> A request must be owned before it is decided, so {@code assigned_to}
 * always identifies who took responsibility for the outcome. A processor who wants
 * to decide immediately claims first — the API offers that sequence rather than a
 * blind status write.
 *
 * <p>COMPLETED, DECLINED and CANCELLED are terminal.
 */
public enum ManufacturingTimeRequestStatus {

    PENDING,
    IN_REVIEW,
    COMPLETED,
    DECLINED,
    CANCELLED;

    private static final Set<ManufacturingTimeRequestStatus> FROM_PENDING =
            Set.of(IN_REVIEW, CANCELLED);

    private static final Set<ManufacturingTimeRequestStatus> FROM_IN_REVIEW =
            Set.of(COMPLETED, DECLINED, CANCELLED);

    /** Still moving: may be assigned, processed or cancelled. */
    public boolean isOpen() {
        return this == PENDING || this == IN_REVIEW;
    }

    public boolean canTransitionTo(ManufacturingTimeRequestStatus target) {
        return switch (this) {
            case PENDING -> FROM_PENDING.contains(target);
            case IN_REVIEW -> FROM_IN_REVIEW.contains(target);
            default -> false;
        };
    }
}
