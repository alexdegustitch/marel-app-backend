package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import java.util.List;

/**
 * The answer to "which payslips are mine".
 *
 * <p>An envelope rather than a bare list, because an empty list has two very
 * different meanings and the screen has to tell them apart. An account that is
 * not linked to a worker — every administration and payroll account — has no
 * payslips because it is not a person who gets paid. A linked account with an
 * empty list has none YET, because no month has been finished for them.
 *
 * <p>Returned as a plain list, the two would both render as "nema obračuna",
 * which for a worker whose months simply are not locked yet reads as a mistake
 * in their pay rather than a state of the calendar.
 *
 * <p>The worker's OWN name and number travel with it, not the account's. A
 * payslip is a document about the employee record: it is that name and that
 * employee number which appear on the paper, and the two can honestly differ
 * from what the sign-in account is called.
 *
 * @param linkedToEmployee false for accounts that are not a worker. Not an
 *                         error — the normal case for administration and
 *                         payroll.
 * @param employeeName     the worker's name as payroll knows it; null when the
 *                         account is not a worker.
 * @param employeeNo       their works number, for the payslip header.
 * @param payrolls         the finished months, newest first. Empty when the
 *                         worker has none yet.
 */
public record MyPayrollsResponse(
        boolean linkedToEmployee,
        String employeeName,
        String employeeNo,
        List<MyPayrollSummaryDto> payrolls
) {
    public static MyPayrollsResponse notAWorker() {
        return new MyPayrollsResponse(false, null, null, List.of());
    }
}
