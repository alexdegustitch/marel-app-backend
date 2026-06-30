package com.aleksandarparipovic.marel_app.employee.dto;

import lombok.Data;

@Data
public class EmployeeBasicInfoDto {
    private Long id;
    private String fullName;
    private String employeeNo;
    private String notes;
}
