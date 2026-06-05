package com.aleksandarparipovic.marel_app.employee.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
public class EmployeePatchRequest {

    private String employeeNo;
    private String fullName;
    private Long departmentId;
    private Long categoryId;
    private Boolean foreigner;
    private BigDecimal transportAllowanceRsd;
    private LocalDate employmentStartDate;
    private LocalDate employmentEndDate;
    private Boolean active;
    private Integer normGraceDays;
    private String notes;
}

