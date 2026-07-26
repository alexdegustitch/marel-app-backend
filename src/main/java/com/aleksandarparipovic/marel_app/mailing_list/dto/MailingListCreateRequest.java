package com.aleksandarparipovic.marel_app.mailing_list.dto;

import com.aleksandarparipovic.marel_app.mailing_list.MailingListVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Owner and timestamps are server-derived; only these fields are client input. */
@Getter
@Setter
public class MailingListCreateRequest {

    @NotBlank(message = "Naziv liste je obavezan")
    @Size(max = 150, message = "Naziv može imati najviše 150 karaktera")
    private String name;

    @Size(max = 1000, message = "Opis može imati najviše 1000 karaktera")
    private String description;

    private MailingListVisibility visibility = MailingListVisibility.PRIVATE;
}
