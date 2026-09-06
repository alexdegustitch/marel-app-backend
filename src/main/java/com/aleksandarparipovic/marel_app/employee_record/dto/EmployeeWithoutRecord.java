package com.aleksandarparipovic.marel_app.employee_record.dto;

/**
 * An active worker who has no karton for a month.
 *
 * <p>The same identity columns a karton row carries — name, number, department,
 * bonus, compensation scheme — so the month's list can show the two kinds of
 * row side by side without the screen having to look anything else up.
 *
 * <p>An interface projection because the query is native; nothing here is an
 * entity.
 */
public interface EmployeeWithoutRecord {
    Long getEmployeeId();

    String getEmployeeName();

    String getEmployeeNo();

    String getEmployeeDepartment();

    String getEmployeeBonus();

    String getEmployeeSchemeCode();
}
