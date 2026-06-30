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
    private String transportAllowanceMode;

    private String notes;

    private String mobilePhone;
    private BigDecimal hourlyRate;
    private Long defaultWorkCategoryId;
    private String defaultWorkCategoryName;
    private boolean worksInCommercial;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime archivedAt;

    private boolean currentlyEmployed;
}
