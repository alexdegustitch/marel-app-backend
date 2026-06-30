package com.aleksandarparipovic.marel_app.notification;

public record MonthlyReportEvent(
        Long employeeRecordId,
        String eventType
) {
    public MonthlyReportEvent(Long employeeRecordId) {
        this(employeeRecordId, "MONTHLY_REPORT_UPDATED");
    }
}
