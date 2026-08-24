package com.aleksandarparipovic.marel_app.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailChangeStartRequest {

    @NotBlank(message = "Unesite novu e-adresu.")
    @Email(message = "E-adresa nije ispravna.")
    @Size(max = 255, message = "E-adresa je predugačka.")
    private String newEmail;

    /** A live session is not proof of identity; knowing the password is. */
    @NotBlank(message = "Unesite lozinku.")
    private String currentPassword;
}
