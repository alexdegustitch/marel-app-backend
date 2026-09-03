package com.aleksandarparipovic.marel_app.payroll_run.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One month of obračuni summed up, straight from the database. */
public interface PayrollMonthAggregate {
    Integer getMonth();
    Long getItemCount();
    Long getDraftCount();
    Long getApprovedCount();
    Long getLockedCount();
    BigDecimal getTotalNetPayable();
    BigDecimal getTotalNetEarnings();
    Instant getLastActivityAt();
}
