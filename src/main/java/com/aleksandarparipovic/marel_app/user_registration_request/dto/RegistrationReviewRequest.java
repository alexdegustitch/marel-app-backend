package com.aleksandarparipovic.marel_app.user_registration_request.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * The body of a withdrawal. Everything else about the outcome — actor identity,
 * timestamp, resulting status, the user's account status — is derived on the
 * server from the security context and the current transaction.
 *
 * <p>Approving and refusing no longer use this: those two are decisions about
 * somebody else's account and each carries a password, so they have their own
 * bodies ({@link RegistrationApproveRequest}, {@link RegistrationDeclineRequest}).
 */
@Getter
@Setter
public class RegistrationReviewRequest {

    /**
     * Why the request was withdrawn. Optional — a person cancelling their own
     * registration owes nobody a reason. Bounded to match the column so an
     * oversized note fails validation rather than the database.
     */
    @Size(max = 1000, message = "Napomena može imati najviše 1000 karaktera")
    private String reviewNote;
}
