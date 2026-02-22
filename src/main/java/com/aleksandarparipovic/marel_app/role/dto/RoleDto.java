package com.aleksandarparipovic.marel_app.role.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RoleDto {
    private Long id;
    private String roleName;
}
