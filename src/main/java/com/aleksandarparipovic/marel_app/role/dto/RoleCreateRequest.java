package com.aleksandarparipovic.marel_app.role.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleCreateRequest {

    @NotBlank(message = "Role name is required")
    private String name;
}
