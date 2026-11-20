package com.aleksandarparipovic.marel_app.payroll_run_item.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One handed-over obračun on the director's "Predati obračuni" list.
 *
 * <p>Served only to holders of PAYROLL_LOCK, so the amounts travel unmasked.
 * {@code sentNote} is filled when the payroll was sent alone — the reason the
 * sender owed for it; a month sent whole carries none.
 */
public interface SentPayrollRow {
    Long getId();
    Long getEmployeeId();
    String getEmployeeName();
    String getEmployeeNo();
    Long getMonthlyReportId();
    Instant getApprovedAt();
    Instant getSentAt();
    String getSentByName();
    String getSentNote();
    BigDecimal getNetPayableAmount();
    BigDecimal getTotalNetEarnings();
}
