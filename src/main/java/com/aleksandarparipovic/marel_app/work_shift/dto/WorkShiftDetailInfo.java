package com.aleksandarparipovic.marel_app.work_shift.dto;


import java.time.Instant;
import java.time.LocalDate;

public interface WorkShiftDetailInfo  {

    Long getId();

    LocalDate getWorkDate();

    Long getSupervisorId();

    String getSupervisorFullName();

    Instant getStartAt();

    Instant getEndAt();

    Integer getTotalMinutes();

    String getNote();

    Long getEmployeeId();

    String getEmployeeName();

}