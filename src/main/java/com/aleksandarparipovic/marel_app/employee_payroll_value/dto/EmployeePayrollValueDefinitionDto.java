package com.aleksandarparipovic.marel_app.employee_payroll_value.dto;

import com.aleksandarparipovic.marel_app.employee_payroll_value.EmployeePayrollValueDefinition;
import lombok.Getter;

/** A value type an employee can be given, as a screen needs to know it. */
@Getter
public class EmployeePayrollValueDefinitionDto {

    private final Long id;
    private final String code;
    private final String name;
    private final String description;
    /** NUMERIC or BOOLEAN — the form asks for a number or a yes/no accordingly. */
    private final String valueType;
    private final String unitCode;

    public EmployeePayrollValueDefinitionDto(EmployeePayrollValueDefinition definition) {
        this.id = definition.getId();
        this.code = definition.getCode();
        this.name = definition.getName();
        this.description = definition.getDescription();
        this.valueType = definition.getValueType();
        this.unitCode = definition.getUnitCode();
    }
}
