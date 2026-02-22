package com.aleksandarparipovic.marel_app.bonus;

import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryOptionDto;
import com.aleksandarparipovic.marel_app.department.Department;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentOptionDto;
import org.springframework.stereotype.Component;
import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryDto;

@Component
public class BonusCategoryMapper {

    public BonusCategoryDto toDto(BonusCategory e) {
        BonusCategoryDto dto = new BonusCategoryDto();

        dto.setId(e.getId());
        dto.setCategoryNo(e.getCategoryNo());
        dto.setCategoryName(e.getCategoryName());
        dto.setBonusAmount(e.getBonusAmount());
        dto.setMinHours(e.getMinHours());
        dto.setDescription(e.getDescription());

        dto.setActive(e.isActive());
        dto.setValidFrom(e.getValidFrom());
        dto.setValidUntil(e.getValidUntil());

        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setArchivedAt(e.getArchivedAt());

        dto.setCurrentlyValid(e.isCurrentlyValid());

        return dto;
    }

    public BonusCategoryOptionDto toOptionDto(BonusCategory category) {
        return new BonusCategoryOptionDto(category.getId(), category.getCategoryNo(), category.getBonusAmount());
    }
}
