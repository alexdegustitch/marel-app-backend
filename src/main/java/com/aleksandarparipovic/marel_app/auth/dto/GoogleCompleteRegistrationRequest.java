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

    /**
     * The account's local password, chosen at registration.
     *
     * <p>Required even though the identity came from Google: every signed
     * action in the application (archiving, approving, freezing a payroll) is
     * confirmed with the LOCAL password, and an account without one would be
     * permanently unable to sign anything. Google remains the convenient way
     * in; the password is the signature.
     */
    @NotBlank(message = "Lozinka je obavezna.")
    private String password;

    @NotBlank(message = "Potvrdite lozinku.")
    private String confirmPassword;
}
