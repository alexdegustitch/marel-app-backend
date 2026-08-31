package com.aleksandarparipovic.marel_app.payroll_change_request.dto;

import com.aleksandarparipovic.marel_app.payroll_change_request.PayrollChangeRequestStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One request to reopen a payroll, as the queue and the payroll's own page need
 * it.
 *
 * <p>Carries enough to decide WITHOUT opening the payroll — whose month it is,
 * which month, and why it is being asked for — because the queue is read as a
 * list. The link to the payroll is {@code monthlyReportId}, which is what the
 * screen routes on.
 */
public record PayrollChangeRequestResponse(
        Long id,
        Long payrollRunItemId,
        /**
         * The three the payroll's own route is built from. All null together
         * when the item has no monthly report behind it, which is the one case
         * where the screen has nowhere to send the reader.
         */
        Long monthlyReportId,
        Long employeeRecordId,
        Long employeeId,
        String employeeName,
        /** First day of the month the payroll covers. */
        LocalDate period,
        /** What the payroll's status was when the request was made, and is now. */
        String payrollStatus,
        Long requestedByUserId,
        String requestedByName,
        OffsetDateTime requestedAt,
        String reason,
        PayrollChangeRequestStatus status,
        Long decidedByUserId,
        String decidedByName,
        OffsetDateTime decidedAt,
        String decisionNote
) {
}
