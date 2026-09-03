package com.aleksandarparipovic.marel_app.payroll_run.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A whole year of obračuni in one answer: twelve months, each with how many
 * there are, how far along they are, what they add up to, and the ones the
 * caller last had open.
 *
 * <p>What a caller may see follows {@code PayrollVisibilityPolicy}, applied in
 * the RESPONSE: for somebody without payroll access both sums are null, the
 * locked count is null, and every locked month is counted as approved — the
 * state they know as "ready". {@code amountsVisible} says which of the two
 * shapes this is, so the screen has no rule of its own to get wrong.
 */
public record PayrollYearOverview(int year, boolean amountsVisible, List<MonthOverview> months) {

    public record MonthOverview(
            int month,
            long itemCount,
            long draftCount,
            long approvedCount,
            Long lockedCount,
            BigDecimal totalNetPayable,
            BigDecimal totalNetEarnings,
            Instant lastActivityAt,
            List<RecentPayroll> recent) {

        public static MonthOverview empty(int month, boolean amountsVisible) {
            return new MonthOverview(month, 0, 0, 0, amountsVisible ? 0L : null, null, null, null, List.of());
        }

        /** The same month as somebody without payroll access is told about it. */
        public MonthOverview withoutAmounts() {
            return new MonthOverview(
                    month,
                    itemCount,
                    draftCount,
                    approvedCount + (lockedCount == null ? 0 : lockedCount),
                    null,
                    null,
                    null,
                    lastActivityAt,
                    recent);
        }
    }

    public record RecentPayroll(Long monthlyReportId, Long employeeId, String employeeName, Instant updateTime) {
    }
}
