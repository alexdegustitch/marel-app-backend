package com.aleksandarparipovic.marel_app.work_log.interval;

/**
 * Interval-level coefficient classification.
 *
 * <p>This is derived per interval, never stored on an operation: an operation that
 * is only partly inside a PLB interval is only partly PLB, and never becomes wholly
 * PLB because one portion qualifies.
 */
public enum CoefficientType {

    /** No parallel-capable operation active — ordinary work, neither PL nor PLB. */
    ORDINARY,

    /** At least one parallel-capable operation active, below the PLB threshold. */
    PL,

    /** Three or more parallel-capable operations simultaneously active. */
    PLB
}
