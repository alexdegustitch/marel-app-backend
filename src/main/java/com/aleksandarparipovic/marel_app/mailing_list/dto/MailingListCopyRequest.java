package com.aleksandarparipovic.marel_app.mailing_list.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * What to call the copy. Everything else about it is decided by the server.
 *
 * <p>No visibility field: a copy is always the copier's own PRIVATE list.
 * Copying somebody's global list and having the result land back in the shared
 * pool would let a private edit reach everybody, which is the opposite of why
 * anybody copies rather than attaches.
 */
@Getter
@Setter
public class MailingListCopyRequest {

    /** Blank falls back to "&lt;source name&gt; (kopija)". */
    @Size(max = 150, message = "Naziv može imati najviše 150 karaktera")
    private String name;
}
