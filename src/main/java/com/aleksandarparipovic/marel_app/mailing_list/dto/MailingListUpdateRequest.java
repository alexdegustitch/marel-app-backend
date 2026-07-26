package com.aleksandarparipovic.marel_app.mailing_list.dto;

import com.aleksandarparipovic.marel_app.mailing_list.MailingListVisibility;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Null means "leave unchanged" for every field. */
@Getter
@Setter
public class MailingListUpdateRequest {

    @Size(max = 150, message = "Naziv može imati najviše 150 karaktera")
    private String name;

    @Size(max = 1000, message = "Opis može imati najviše 1000 karaktera")
    private String description;

    private MailingListVisibility visibility;
}
