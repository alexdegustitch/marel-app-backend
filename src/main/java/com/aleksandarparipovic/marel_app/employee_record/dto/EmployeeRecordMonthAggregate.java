package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One month of a year, summed up — what the Kartoni year view shows on a month
 * card before anybody opens it.
 *
 * <p>An interface projection because the query is native and grouped; nothing
 * here is an entity.
 */
public interface EmployeeRecordMonthAggregate {
    Integer getMonth();
    Long getRecordCount();
    Long getEmployeeCount();
    Long getTotalShiftMinutes();
    BigDecimal getAvgPerformanceRate();
    Instant getLastActivityAt();
}
