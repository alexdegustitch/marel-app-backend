package com.aleksandarparipovic.marel_app.employee_record.dto;

/** A karton found by a fragment of the worker's name or number. */
public interface EmployeeRecordSearchHit {
    Long getEmployeeRecordId();
    Long getEmployeeId();
    String getEmployeeName();
    String getEmployeeNo();
    Integer getMonth();
    Integer getYear();
}
