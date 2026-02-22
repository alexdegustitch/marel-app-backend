package com.aleksandarparipovic.marel_app.employee.view;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@AllArgsConstructor
@Getter
public class EmployeeWithBonusView {

    private final Long employeeId;
    private final String employeeNo;
    private final String fullName;
    private final String departmentName;
    private final Long departmentId;
    private final LocalDate employmentStartDate;
    private final LocalDate probationEndDate;
    private final String notes;
    private final BigDecimal transportAllowanceRsd;
    private final String categoryNo;
    private final Long categoryId;
    private final String categoryName;
    private final BigDecimal bonusAmount;
    private final LocalDate bonusStart;
    private final Boolean foreigner;
}

