package com.aleksandarparipovic.marel_app.department;

import com.aleksandarparipovic.marel_app.department.dto.DepartmentDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentOptionDto;
import org.springframework.stereotype.Component;


@Component
public class DepartmentMapper {

    public DepartmentDto toDto(Department d) {
        if (d == null) return null;

        return DepartmentDto.builder()
                .id(d.getId())
                .name(d.getName())
                .description(d.getDescription())
                .active(d.getActive())
                .build();
    }
    public DepartmentOptionDto toOptionDto(Department d) {
        return new DepartmentOptionDto(d.getId(), d.getName());
    }
}

