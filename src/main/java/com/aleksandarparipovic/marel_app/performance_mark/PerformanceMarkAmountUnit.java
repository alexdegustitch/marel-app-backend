package com.aleksandarparipovic.marel_app.performance_mark;

/**
 * How a performance mark's amount is read against the hourly rate. The set is
 * closed on purpose — it mirrors chk_pm_amount_unit in the database, and
 * widening it is one ALTER of that CHECK plus a constant here.
 */
public enum PerformanceMarkAmountUnit {
    /** The amount is a percentage of the hourly rate: -40 → rate × 0.60. */
    PERCENT,
    /** The amount is dinars per hour: -40 → rate − 40 RSD. */
    RSD_PER_HOUR
}
