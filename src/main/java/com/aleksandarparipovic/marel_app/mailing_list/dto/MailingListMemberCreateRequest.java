package com.aleksandarparipovic.marel_app.mailing_list.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Exactly one of userId / externalEmail must be supplied — validated in the
 * service and, as a last line of defence, by a database check constraint.
 */
@Getter
@Setter
public class MailingListMemberCreateRequest {

    private Long userId;

    @Email(message = "Email adresa nije validna")
    @Size(max = 320, message = "Email adresa je predugačka")
    private String externalEmail;

    @Size(max = 150, message = "Naziv može imati najviše 150 karaktera")
    private String displayName;
}
