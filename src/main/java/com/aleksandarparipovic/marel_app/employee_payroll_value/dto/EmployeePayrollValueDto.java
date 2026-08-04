package com.aleksandarparipovic.marel_app.employee_payroll_value.dto;

import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueHistory;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One period of one value. {@code validUntil} is the INCLUSIVE last day. */
@Getter
public class EmployeePayrollValueDto {

    private final Long id;
    private final String code;
    private final String name;
    private final String unitCode;
    private final BigDecimal numericValue;
    private final LocalDate validFrom;
    private final LocalDate validUntil;
    private final boolean current;
    private final String note;
    private final OffsetDateTime createdAt;

    public EmployeePayrollValueDto(EmployeePayrollValueHistory h) {
        this.id = h.getId();
        this.code = h.getDefinition().getCode();
        this.name = h.getDefinition().getName();
        this.unitCode = h.getDefinition().getUnitCode();
        this.numericValue = h.getNumericValue();
        this.validFrom = h.getValidFrom();
        this.validUntil = h.getValidUntil();
        this.current = h.getValidUntil() == null && h.getArchivedAt() == null;
        this.note = h.getNote();
        this.createdAt = h.getCreatedAt();
    }
}
