package com.aleksandarparipovic.marel_app.role;

import com.aleksandarparipovic.marel_app.role.dto.RoleDto;

public class RoleMapper {

    public static RoleDto toDto(Role role) {
        return RoleDto.builder()
                .id(role.getId())
                .roleName(role.getRoleName())
                .build();
    }
}
