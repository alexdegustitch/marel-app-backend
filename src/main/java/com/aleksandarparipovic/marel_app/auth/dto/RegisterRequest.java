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

    @NotBlank(message = "Šifra je obavezna")
    @Size(min = 4, message = "Šifra mora imati bar 4 karaktera")
    private String password;

    @NotBlank(message = "Potvrda šifre je obavezna")
    private String confirmPassword;

    @NotNull(message = "Uloga je obavezna")
    private Long roleId;
}
