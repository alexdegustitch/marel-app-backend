package com.aleksandarparipovic.marel_app.employee.dto;

import java.util.List;

/**
 * The figures the employee directory states above its table, for ONE filter
 * state: how many people match, how many of them are on each compensation
 * scheme, and how many are still on probation.
 *
 * <p>One request instead of four. The screen used to ask the search endpoint
 * for a page of size one per scheme just to read {@code totalElements}, which
 * was three extra COUNT(DISTINCT) queries per keystroke — on a table of
 * millions that is the whole cost of the page, paid four times over.
 *
 * <p>The scheme codes are carried RAW, in the same spirit as
 * {@code EmployeeWithBonusView}: which code is "stranci" and which is
 * "komercijala" is decided on the client, so a scheme added by a migration
 * simply appears as one more count.
 */
public record EmployeeDirectorySummary(
        long total,
        long onProbation,
        List<SchemeCount> bySchemeCode) {

    /** {@code schemeCode} is null for employees with no open scheme period. */
    public record SchemeCount(String schemeCode, String schemeName, long count) {
    }
}
