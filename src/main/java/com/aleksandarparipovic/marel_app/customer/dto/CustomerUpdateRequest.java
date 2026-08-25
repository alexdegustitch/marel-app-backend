package com.aleksandarparipovic.marel_app.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Null means "leave it", as everywhere else in this codebase.
 *
 * <p>Which is why every optional field is cleared by sending a BLANK string
 * rather than null: a customer who no longer has a website has to be able to say
 * so, and null is already spoken for.
 */
@Getter
@Setter
public class CustomerUpdateRequest {

    @Size(max = 255, message = "Naziv je predugačak.")
    private String name;

    @Size(max = 50, message = "Šifra je predugačka.")
    private String code;

    @Size(max = 50, message = "PIB je predugačak.")
    private String taxId;

    @Size(max = 500, message = "Adresa sajta je predugačka.")
    private String website;

    @Email(message = "Neispravna e-adresa.")
    @Size(max = 255, message = "E-adresa je predugačka.")
    private String email;

    @Size(max = 50, message = "Broj telefona je predugačak.")
    private String phone;

    private Boolean active;
}
