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

    /**
     * TRUE for the two categories that stand for time nobody worked.
     *
     * <p>Both are drawn on the shift as a work log — NO because a supervisor
     * records a whole day nobody came in that way, ND because the application
     * writes it when the overtime bank covers such a day. Neither is WORK, and
     * neither may be measured as any: they carry no coefficient, they are not
     * time present, and the minutes they stand for reach the day's totals through
     * the absence record instead. See {@code DailyRecalcService}.
     */
    public static boolean isAbsenceLog(String categoryNo) {
        return UNPAID_ABSENCE.equals(categoryNo) || NON_WORKING_DAY.equals(categoryNo);
    }

    private AbsenceCategoryCodes() {
    }
}
