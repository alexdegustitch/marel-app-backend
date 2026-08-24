package com.aleksandarparipovic.marel_app.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Changing one's own password.
 *
 * <p>Only presence is validated here. The STRENGTH rules live in
 * {@code PasswordPolicy}, because they are the same rules registration and an
 * administrator's reset must apply, and a copy of them in a Bean Validation
 * annotation is a copy that drifts.
 */
@Getter
@Setter
public class PasswordChangeRequest {

    @NotBlank(message = "Unesite trenutnu lozinku.")
    private String currentPassword;

    @NotBlank(message = "Unesite novu lozinku.")
    private String newPassword;

    /**
     * Typed a second time.
     *
     * <p>Checked on the server as well as in the form: the form's check is a
     * courtesy to whoever is typing, and a client that skipped it would otherwise
     * set a password its owner never successfully typed twice — which they then
     * cannot sign in with.
     */
    @NotBlank(message = "Potvrdite novu lozinku.")
    private String confirmPassword;
}
