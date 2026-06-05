package com.aleksandarparipovic.marel_app.notification;

import java.time.LocalDate;

public record DailyReportEvent(
        Long employeeId,
        LocalDate workDate,
        Long workShiftId,
        String eventType
) {
    public DailyReportEvent(Long employeeId, LocalDate workDate, Long workShiftId) {
        this(employeeId, workDate, workShiftId, "DAILY_REPORT_UPDATED");
    }
}
