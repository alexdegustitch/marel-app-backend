package com.aleksandarparipovic.marel_app.department.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DepartmentCreateRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String description;
}
