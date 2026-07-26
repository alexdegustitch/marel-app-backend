package com.aleksandarparipovic.marel_app.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleCompleteRegistrationRequest {

    @NotBlank(message = "Register code is required")
    private String registerCode;

    @NotNull(message = "Role is required")
    private Long roleId;

    private String mobilePhone;
}
