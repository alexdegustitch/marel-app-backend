package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One prior FINISHED (LOCKED) payroll month, for the payslip's mini chart.
 *
 * <p>The month being here says "there is a finished obračun for this month";
 * {@link #getMark()} says what ocena it applied, and is null when none was —
 * the chart then draws an empty column under the month's name, which is a
 * different statement from the month being absent altogether. The value is the
 * ocena itself — the šifarnik version's mark, or the legacy 0–2 multiplier on
 * months marked before the šifarnik — never the amount it did to the rate.
 */
@Getter
@RequiredArgsConstructor
public class PayrollMarkHistoryDto {
    /** First day of the finished payroll month. */
    private final LocalDate period;
    /** The APPLIED ocena, or null for a finished month without one. */
    private final BigDecimal mark;
}
