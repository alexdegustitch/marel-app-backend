package com.aleksandarparipovic.marel_app.user_registration_request.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Everything an approval accepts: the reviewer's own password, and nothing else.
 *
 * <p>No note. Approving is the ordinary outcome and needs no justification —
 * the applicant is simply told the account is active. A refusal is the decision
 * that owes an explanation, and that one is {@link RegistrationDeclineRequest}.
 *
 * <p>The password is not a second authentication: the caller is already signed
 * in and already carries the permission. It is there so that an unattended
 * session cannot activate an account with one click.
 */
public record RegistrationApproveRequest(
        @NotBlank(message = "Lozinka je obavezna") String password
) {
}
