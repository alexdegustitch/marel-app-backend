package com.aleksandarparipovic.marel_app.compensation_scheme.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The ONE field the catalogue may edit on a scheme — everything else is seeded. */
@Getter
@NoArgsConstructor
public class UpdateSchemeNoteRequest {

    @Size(max = 2000, message = "Napomena je predugačka (najviše 2000 znakova).")
    private String note;
}
