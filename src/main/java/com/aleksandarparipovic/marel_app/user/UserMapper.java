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
                .displayName(user.getDisplayName())
                .mobilePhone(user.getMobilePhone())
                .emailAddress(user.getEmailAddress())
                .roleName(user.getRole().getRoleName())
                .active(user.getActive())
                .hasPassword(user.getPasswordHash() != null)
                // Most accounts are not workers, so for most rows this reads a
                // null field and asks the database nothing. Only a LINKED account
                // costs a query for the name, which bounds the cost of a page of
                // users by how many of them are workers rather than by page size.
                .employeeId(user.getEmployee() != null ? user.getEmployee().getId() : null)
                .employeeName(user.getEmployee() != null ? user.getEmployee().getFullName() : null)
                .build();
    }
}
