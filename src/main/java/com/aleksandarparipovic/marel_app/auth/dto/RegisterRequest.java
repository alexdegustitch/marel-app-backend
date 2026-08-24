package com.aleksandarparipovic.marel_app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "Ime je obavezno")
    private String firstName;

    @NotBlank(message = "Prezime je obavezno")
    private String lastName;

    @NotBlank(message = "Email adresa je obavezna")
    @Email(message = "Email adresa nije validna")
    private String emailAddress;

    private String mobilePhone;

    /**
     * Chosen at registration and never again.
     *
     * <p>Optional here: left out, the server derives one from the e-mail address
     * (dijana.rad@gmail.com → dijana.rad), which is the name the person already
     * thinks of as theirs. The form offers that suggestion filled in and lets them
     * type something else — this is their only chance, since a username is frozen
     * the moment the account exists.
     */
    @Size(max = 32, message = "Korisničko ime je predugačko")
    private String username;

    /**
     * Length and composition are checked by {@code PasswordPolicy}, not here.
     *
     * <p>The annotation used to say four characters. Keeping a second, weaker copy
     * of the rules in an annotation is how a password accepted at registration
     * becomes one its owner cannot re-enter when they later change it.
     */
    @NotBlank(message = "Šifra je obavezna")
    private String password;

    @NotBlank(message = "Potvrda šifre je obavezna")
    private String confirmPassword;

    @NotNull(message = "Uloga je obavezna")
    private Long roleId;
}
