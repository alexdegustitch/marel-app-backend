package com.aleksandarparipovic.marel_app.production_order_recipient.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManualRecipientRequest {

    @NotBlank(message = "Email adresa je obavezna")
    @Email(message = "Email adresa nije validna")
    @Size(max = 320, message = "Email adresa je predugačka")
    private String email;

    @Size(max = 150, message = "Naziv može imati najviše 150 karaktera")
    private String name;
}
