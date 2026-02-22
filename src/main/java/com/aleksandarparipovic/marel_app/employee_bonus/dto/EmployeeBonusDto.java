package com.aleksandarparipovic.marel_app.employee_bonus.dto;

import lombok.*;
import java.time.*;

@Data
public class EmployeeBonusDto {

    private Long id;
    private Long employeeId;
    private String employeeName;

    private Long bonusCategoryId;
    private String bonusCategoryName;

    private LocalDate startDate;
    private LocalDate endDate;

    private Long changedById;
    private String changedByUsername;

    private OffsetDateTime createdAt;
    private boolean active;
}
