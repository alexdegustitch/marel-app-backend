package com.aleksandarparipovic.marel_app.department.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DepartmentDto {

    private Long id;
    private String name;
    private String description;
    private Boolean active;
}
