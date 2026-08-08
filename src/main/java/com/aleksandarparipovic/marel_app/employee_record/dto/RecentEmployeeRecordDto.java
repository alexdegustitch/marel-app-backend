package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.math.BigDecimal;

public interface RecentEmployeeRecordDto {
    Long getId();
    String getEmployeeName();
    Long getEmployeeId();
    Integer getMonth();
    Integer getYear();

    /**
     * The APPROVED performance for the month — what is paid, not what was
     * measured. Null when the month has no monthly report yet, which is an
     * ordinary state for a record that has just been created.
     */
    BigDecimal getApprovedPerformanceRate();

    /** Total minutes on shift that month; null for the same reason. */
    Integer getTotalShiftMinutes();
}
