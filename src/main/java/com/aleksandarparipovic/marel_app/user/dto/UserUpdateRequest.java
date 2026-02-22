package com.aleksandarparipovic.marel_app.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {

    private String username;

    @Email(message = "Email must be valid")
    private String emailAddress;

    // optional
    @Size(min = 4, message = "Password must be at least 4 characters")
    private String password;

    private String roleName;

    private Boolean active;
}
