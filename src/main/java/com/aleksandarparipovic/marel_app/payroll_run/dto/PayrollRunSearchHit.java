package com.aleksandarparipovic.marel_app.payroll_run.dto;

/**
 * A payroll month found by search, as reported to the caller.
 *
 * <p>A record rather than the projection itself so the status can pass through
 * {@code PayrollVisibilityPolicy.visibleStatus} — a projection cannot be edited,
 * and a LOCKED month must reach somebody without payroll access as APPROVED.
 */
public record PayrollRunSearchHit(
        Long monthlyReportId,
        Long employeeId,
        String employeeName,
        String employeeNo,
        int month,
        int year,
        String status) {

    public static PayrollRunSearchHit of(PayrollRunSearchRow row, String visibleStatus) {
        return new PayrollRunSearchHit(
                row.getMonthlyReportId(),
                row.getEmployeeId(),
                row.getEmployeeName(),
                row.getEmployeeNo(),
                row.getMonth(),
                row.getYear(),
                visibleStatus);
    }
}
