package com.aleksandarparipovic.marel_app.employee;

import com.aleksandarparipovic.marel_app.employee.dto.EmployeeBasicInfoDto;
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
        dto.setTransportAllowanceMode(e.getTransportAllowanceMode());

        dto.setNotes(e.getNotes());

        dto.setMobilePhone(e.getMobilePhone());
        dto.setHourlyRate(e.getHourlyRate());
        dto.setDefaultWorkCategoryId(e.getDefaultWorkCategory() != null ? e.getDefaultWorkCategory().getId() : null);
        dto.setDefaultWorkCategoryName(e.getDefaultWorkCategory() != null ? e.getDefaultWorkCategory().getCategoryName() : null);
        dto.setWorksInCommercial(e.isWorksInCommercial());

        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setArchivedAt(e.getArchivedAt());

        dto.setCurrentlyEmployed(e.isCurrentlyEmployed());

        return dto;
    }

    public EmployeeBasicInfoDto toBasicInfoDto(Employee e){
        EmployeeBasicInfoDto employeeBasicInfoDto = new EmployeeBasicInfoDto();
        employeeBasicInfoDto.setId(e.getId());
        employeeBasicInfoDto.setFullName(e.getFullName());
        employeeBasicInfoDto.setEmployeeNo(e.getEmployeeNo());
        employeeBasicInfoDto.setNotes(e.getNotes());
        return employeeBasicInfoDto;
    }
}
