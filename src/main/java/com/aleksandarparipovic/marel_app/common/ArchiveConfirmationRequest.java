package com.aleksandarparipovic.marel_app.common;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The body of a catalogue archive: nothing but the caller's password, the
 * signature under the action. Shared by every catalogue controller that
 * archives (families, types, departments, bonus categories).
 */
@Getter
@NoArgsConstructor
public class ArchiveConfirmationRequest {

    @NotBlank(message = "Lozinka je obavezna.")
    private String password;
}
