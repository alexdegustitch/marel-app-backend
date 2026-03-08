package com.aleksandarparipovic.marel_app.work_shift.dto;

import java.time.Instant;
import java.time.OffsetDateTime;

public interface WorkShiftInfo {

    Long getId();

    String getEmployeeName();

    Long getEmployeeId();

    String getEmployeeNo();

    Boolean getEmployeeForeigner();

    String getEmployeeDepartment();

    String getEmployeeBonus();

    Instant getUpdateTime();

}