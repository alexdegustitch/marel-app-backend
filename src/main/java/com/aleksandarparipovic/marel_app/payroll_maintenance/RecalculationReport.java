package com.aleksandarparipovic.marel_app.payroll_maintenance;

import java.util.List;

/**
 * What a maintenance sweep did.
 *
 * @param visited      items the sweep tried, LOCKED and archived ones excluded
 * @param recalculated items that came through
 * @param failed       items that did not — the number that matters
 * @param failures     the first few, with their reason, so the cause is visible
 *                     without reading the log
 */
public record RecalculationReport(int visited, int recalculated, int failed, List<Failure> failures) {

    public record Failure(Long payrollRunItemId, String reason) {}
}
