package com.aleksandarparipovic.marel_app.notification;

public record MonthlyReportEvent(
        Long employeeId,
        int reportYear,
        int reportMonth,
        String eventType
) {
    public MonthlyReportEvent(Long employeeId, int year, int month) {
        this(employeeId, year, month, "MONTHLY_REPORT_UPDATED");
    }
}
