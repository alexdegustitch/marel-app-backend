package com.aleksandarparipovic.marel_app.payroll_change_request;

/**
 * Where a request to reopen a payroll stands.
 *
 * <p>Three states and no way back: a decided request is a record of what was
 * asked and answered. Asking again is a NEW request, which is what makes the
 * second attempt visible instead of overwriting the first.
 */
public enum PayrollChangeRequestStatus {

    /** Waiting for payroll. At most one per payroll item — uq_pcr_open_per_item. */
    PENDING,

    /** Granted: the payroll went back to DRAFT and the supervisor has it again. */
    ACCEPTED,

    /** Refused. The payroll did not move, and the reason is on the row. */
    DECLINED
}
