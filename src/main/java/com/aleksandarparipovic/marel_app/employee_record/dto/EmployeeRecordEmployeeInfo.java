package com.aleksandarparipovic.marel_app.employee_record.dto;

import java.time.Instant;

public interface EmployeeRecordEmployeeInfo {
    String getEmployeeName();

    Long getEmployeeId();

    Integer getMonth();

    Integer getYear();

    /** When the karton was withdrawn, or null while it is live. See V73. */
    Instant getArchivedAt();

    /** Why it was withdrawn, as the person typed it; null while it is live. */
    String getArchiveReason();
}
