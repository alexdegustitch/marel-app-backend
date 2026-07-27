package com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.dto;

import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistory;

import java.time.LocalDate;

public record EmployeeCompensationSchemeHistoryDto(
        Long id,
        Long employeeId,
        Long compensationSchemeId,
        String compensationSchemeCode,
        String compensationSchemeName,
        LocalDate validFrom,
        LocalDate validUntil,
        String note
) {
    public static EmployeeCompensationSchemeHistoryDto from(EmployeeCompensationSchemeHistory entity) {
        return new EmployeeCompensationSchemeHistoryDto(
                entity.getId(),
                entity.getEmployee().getId(),
                entity.getCompensationScheme().getId(),
                entity.getCompensationScheme().getCode(),
                entity.getCompensationScheme().getName(),
                entity.getValidFrom(),
                entity.getValidUntil(),
                entity.getNote());
    }
}
