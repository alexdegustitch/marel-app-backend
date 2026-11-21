package com.aleksandarparipovic.marel_app.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleUnlinkRequest {

    /**
     * A live session is not proof of identity; knowing the password is — the same
     * rule the password change and the address change already apply, and for the
     * same case: a workstation left unlocked on the factory floor.
     */
    @NotBlank(message = "Unesite lozinku.")
    private String currentPassword;
}
