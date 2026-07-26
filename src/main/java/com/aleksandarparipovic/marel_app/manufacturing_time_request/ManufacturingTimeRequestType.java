package com.aleksandarparipovic.marel_app.manufacturing_time_request;

/**
 * What the requester wants done to a product's manufacturing time.
 *
 * <p>There is no {@code DELETE}: {@code ProductManufacturingTimeService.delete()}
 * is a soft delete (is_active = false), so the domain has no physical delete to
 * request. {@link #DEACTIVATE} names what the system can actually do.
 */
public enum ManufacturingTimeRequestType {

    /** No manufacturing time exists yet for this product; make one. */
    CREATE,

    /** Change the values on an existing record. */
    UPDATE,

    /** Recompute an existing record from current norms. */
    RECALCULATE,

    /** Retire an existing record (soft delete). */
    DEACTIVATE;

    /** Only CREATE has nothing to act on; every other type needs a target. */
    public boolean requiresTarget() {
        return this != CREATE;
    }
}
