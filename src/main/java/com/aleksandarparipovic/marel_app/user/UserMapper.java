package com.aleksandarparipovic.marel_app.user;

import com.aleksandarparipovic.marel_app.user.dto.UserDto;

public class UserMapper {

    public static UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .mobilePhone(user.getMobilePhone())
                .emailAddress(user.getEmailAddress())
                .roleName(user.getRole().getRoleName())
                .active(user.getActive())
                .build();
    }
}
