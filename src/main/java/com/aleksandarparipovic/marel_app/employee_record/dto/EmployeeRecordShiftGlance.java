package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One of a karton's last shifts, as the month list glances at it: which day it
 * was, what category of work it was booked as, and how the day performed.
 *
 * <p>The category is the EFFECTIVE one when a bonus remap applies and the
 * original otherwise — the same answer the karton itself displays for the
 * shift. The rate is the daily report's approved one; a shift whose report is
 * not built yet answers null and the UI shows a neutral badge.
 */
public interface EmployeeRecordShiftGlance {
    Long getEmployeeRecordId();

    Long getWorkShiftId();

    LocalDate getWorkDate();

    String getCategoryNo();

    String getCategoryName();

    BigDecimal getApprovedPerformanceRate();
}
