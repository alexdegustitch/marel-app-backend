package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One finished payroll month, as the person who was paid for it sees it listed.
 *
 * <p>Deliberately carries NO money. The list is a list of documents to open, and
 * the amounts are in the document — putting a net figure on a row would print
 * somebody's pay onto a screen at the moment they only asked which months exist,
 * where anyone standing behind them reads it too.
 *
 * <p>{@code lockedAt} is here because it is the honest date of the payslip: the
 * period says which work it covers, and this says when payroll finished with it.
 */
public record MyPayrollSummaryDto(
        Long monthlyReportId,
        LocalDate period,
        OffsetDateTime lockedAt
) {
    public MyPayrollSummaryDto(PayrollRunItem item) {
        this(
                item.getMonthlyReport() != null ? item.getMonthlyReport().getId() : null,
                item.getPeriod(),
                item.getLockedAt()
        );
    }
}
