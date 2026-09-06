package com.aleksandarparipovic.marel_app.sample_order;

/**
 * The derived states a clicked KPI narrows the sample-order list to.
 *
 * <p>Fewer than a production order's: a sample run has no razrada to be missing,
 * so the only attention states are about the single rok. Both imply an OPEN
 * order — a closed sample order is neither late nor due. Unlike the production
 * side these ARE expressible in SQL (the deadline is one column, not the nearest
 * of several), so they are answered as ordinary specifications, not in memory.
 */
public enum SampleOrderAttention {
    /** Open, and the rok is in the past. */
    LATE,
    /** Open, and the rok is within three days (today included). */
    DUE_SOON
}
