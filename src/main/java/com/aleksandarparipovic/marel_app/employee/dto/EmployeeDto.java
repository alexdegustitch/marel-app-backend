package com.aleksandarparipovic.marel_app.employee.dto;

import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Data
public class EmployeeDto {

    private Long id;
    private String employeeNo;
    private String fullName;

    private Long departmentId;
    private String departmentName;

    private LocalDate employmentStartDate;
    private LocalDate employmentEndDate;

    private boolean active;
    private boolean foreigner;

    private Integer normGraceDays;
    private LocalDate probationEndDate;
    private BigDecimal transportAllowanceRsd;

    private String notes;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime archivedAt;

    private boolean currentlyEmployed;
}
