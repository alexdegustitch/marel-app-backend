package com.aleksandarparipovic.marel_app.mailing_list.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MailingListAccessRequest {

    @NotNull(message = "Korisnik je obavezan")
    private Long userId;
}
