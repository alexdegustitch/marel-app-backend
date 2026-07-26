package com.aleksandarparipovic.marel_app.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserCreateRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @Size(min = 4, message = "Password must be at least 4 characters")
    private String password;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    private String mobilePhone;

    @Email(message = "Invalid email format")
    private String emailAddress;

    @NotBlank(message = "Role is required")
    private String role;
}
