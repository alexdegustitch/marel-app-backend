package com.aleksandarparipovic.marel_app.employee_bonus;

import org.springframework.stereotype.Component;
import com.aleksandarparipovic.marel_app.employee_bonus.dto.EmployeeBonusDto;

@Component
public class EmployeeBonusMapper {

    public EmployeeBonusDto toDto(EmployeeBonus e) {
        EmployeeBonusDto dto = new EmployeeBonusDto();
        dto.setId(e.getId());

        dto.setEmployeeId(e.getEmployee().getId());
        dto.setEmployeeName(e.getEmployee().getFullName());

        dto.setBonusCategoryId(e.getBonusCategory().getId());
        dto.setBonusCategoryName(e.getBonusCategory().getCategoryName());

        dto.setStartDate(e.getStartDate());
        dto.setEndDate(e.getEndDate());

        dto.setChangedById(e.getChangedBy().getId());
        dto.setChangedByUsername(e.getChangedBy().getUsername());

        dto.setCreatedAt(e.getCreatedAt());
        dto.setActive(e.isActive());

        return dto;
    }
}
