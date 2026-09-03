package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A whole year of kartoni in one answer: twelve months, each with its totals
 * and the kartoni the caller last had open in it.
 *
 * <p>Replaces thirteen requests (the year's activity feed plus one
 * "last activity" call per month) with one. Every month is present, zero-filled,
 * so the screen never has to guess whether a missing month is empty or not yet
 * loaded.
 *
 * @param avgPerformanceRate the plain average of the approved performance rate
 *                           over the month's kartoni that have one; null when
 *                           none has
 */
public record EmployeeRecordYearOverview(int year, List<MonthOverview> months) {

    public record MonthOverview(
            int month,
            long recordCount,
            long employeeCount,
            long totalShiftMinutes,
            BigDecimal avgPerformanceRate,
            Instant lastActivityAt,
            List<RecentRecord> recent) {

        public static MonthOverview empty(int month) {
            return new MonthOverview(month, 0, 0, 0, null, null, List.of());
        }
    }

    public record RecentRecord(Long employeeRecordId, Long employeeId, String employeeName, Instant updateTime) {
    }
}
