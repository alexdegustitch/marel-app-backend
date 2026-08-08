package com.aleksandarparipovic.marel_app.employee.dto;

import lombok.*;
import java.time.*;
import java.math.BigDecimal;

@Data
public class EmployeeDto {

    private Long id;
    private String employeeNo;
    private String firstName;
    private String lastName;
    /** Derived from the two parts by the database; never sent back on a write. */
    private String fullName;

    private Long departmentId;
    private String departmentName;

    private LocalDate employmentStartDate;
    private LocalDate employmentEndDate;

    private boolean active;

    private Integer normGraceDays;
    private LocalDate probationEndDate;
    private BigDecimal transportAllowanceRsd;
    private String transportAllowanceMode;

    private String notes;

    private String mobilePhone;
    private String email;
    private BigDecimal hourlyRate;
    private Long defaultWorkCategoryId;
    private String defaultWorkCategoryName;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime archivedAt;

    private boolean currentlyEmployed;
}
