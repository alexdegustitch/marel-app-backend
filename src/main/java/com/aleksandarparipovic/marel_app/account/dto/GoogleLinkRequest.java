package com.aleksandarparipovic.marel_app.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleLinkRequest {

    /** The one-time code the Google round-trip handed back to the application. */
    @NotBlank(message = "Kod je nevažeći. Pokušajte povezivanje ponovo.")
    private String code;
}
