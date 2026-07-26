package com.aleksandarparipovic.marel_app.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserDto {

    private Long id;
    private String username;
    private String firstName;
    private String lastName;
    private String fullName;
    private String mobilePhone;
    private String emailAddress;
    private String roleName;
    private Boolean active;
}
