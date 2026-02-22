package com.aleksandarparipovic.marel_app.employee;

import org.springframework.stereotype.Component;
import com.aleksandarparipovic.marel_app.employee.dto.EmployeeDto;

@Component
public class EmployeeMapper {

    public EmployeeDto toDto(Employee e) {
        EmployeeDto dto = new EmployeeDto();

        dto.setId(e.getId());
        dto.setEmployeeNo(e.getEmployeeNo());
        dto.setFullName(e.getFullName());

        dto.setDepartmentId(e.getDepartment().getId());
        dto.setDepartmentName(e.getDepartment().getName());

        dto.setEmploymentStartDate(e.getEmploymentStartDate());
        dto.setEmploymentEndDate(e.getEmploymentEndDate());

        dto.setActive(e.isActive());
        dto.setForeigner(e.isForeigner());

        dto.setNormGraceDays(e.getNormGraceDays());
        dto.setProbationEndDate(e.getProbationEndDate());
        dto.setTransportAllowanceRsd(e.getTransportAllowanceRsd());

        dto.setNotes(e.getNotes());

        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setArchivedAt(e.getArchivedAt());

        dto.setCurrentlyEmployed(e.isCurrentlyEmployed());

        return dto;
    }
}
