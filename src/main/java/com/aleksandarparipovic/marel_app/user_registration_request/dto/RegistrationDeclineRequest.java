package com.aleksandarparipovic.marel_app.user_registration_request.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A refusal asks for the reason as well as the password.
 *
 * <p>The reason is mandatory because it is sent to the applicant: being turned
 * away with no explanation leaves them with nothing to act on, and leaves the
 * next reviewer with no record of why. Bounded to match the column so an
 * oversized note fails validation rather than the database.
 */
public record RegistrationDeclineRequest(
        @NotBlank(message = "Lozinka je obavezna") String password,

        @NotBlank(message = "Obrazloženje je obavezno")
        @Size(max = 1000, message = "Obrazloženje može imati najviše 1000 karaktera")
        String reviewNote
) {
}
