package com.aleksandarparipovic.marel_app.production_order;

/**
 * The derived states the list header counts and lets the reader filter to by
 * clicking a KPI — the answer to "show me exactly the orders behind that number".
 *
 * <p>None of these is a column. They are read from the SAME machinery the cards
 * and the stats use — the effective deadline (nearest of the order's own dates
 * and any line-item date) and the agreed razrada — so a KPI, the row it points
 * at, and this filter can never disagree. All three imply an OPEN order: a
 * delivered order is neither late nor awaiting a scope.
 */
public enum ProductionOrderAttention {
    /** Open, and the effective deadline is in the past. */
    LATE,
    /** Open, and the effective deadline is within three days (today included). */
    DUE_SOON,
    /** Open, with no agreed razrada — progress cannot be measured. */
    WITHOUT_SCOPE
}
