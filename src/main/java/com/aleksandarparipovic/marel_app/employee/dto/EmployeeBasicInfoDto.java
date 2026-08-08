package com.aleksandarparipovic.marel_app.employee.dto;

import lombok.Data;

@Data
public class EmployeeBasicInfoDto {
    private Long id;
    private String firstName;
    private String lastName;
    /** Derived from the two parts by the database; never sent back on a write. */
    private String fullName;
    private String employeeNo;
    private String notes;
}
