package com.aleksandarparipovic.marel_app.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Only the name is required.
 *
 * <p>Demanding a code or a tax id before anybody can be recorded is how the
 * customer ends up typed into an order's free-text name instead — which is the
 * habit this table exists to end.
 */
@Getter
@Setter
public class CustomerCreateRequest {

    @NotBlank(message = "Naziv kupca je obavezan.")
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
}
