package com.aleksandarparipovic.marel_app.absence_record;

/**
 * The two category codes this feature turns on.
 *
 * <p>Matched by CODE and never by id, for the reason the dashboard already
 * matches by code: a category is re-versioned over time (valid_from /
 * valid_until) and every version keeps the code the factory knows it by. An id
 * pinned here would stop meaning anything the first time somebody re-versions
 * the category.
 */
public final class AbsenceCategoryCodes {

    /** Neplaćeno odsustvo — the only absence the overtime bank can buy back. */
    public static final String UNPAID_ABSENCE = "NO";

    /**
     * Neradni dan — written by the application, never chosen by a person.
     *
     * <p>{@code WorkLogService} refuses a log a person tries to give this
     * category: the only rows that carry it are the ones the allocation writes
     * across a fully covered shift.
     */
    public static final String NON_WORKING_DAY = "ND";

    private AbsenceCategoryCodes() {
    }
}
