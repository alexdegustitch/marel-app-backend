package com.aleksandarparipovic.marel_app.search.dto;

/** Minimal employee row for the global search. */
public interface EmployeeSearchRow {
    Long getId();
    String getFullName();
    String getEmployeeNo();
    String getDepartmentName();
}
