package com.aleksandarparipovic.marel_app.user_registration_request.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * The only thing a reviewer may send. Everything else about the decision —
 * reviewer identity, timestamp, resulting status, the user's new account status —
 * is derived on the server from the security context and the current transaction.
 */
@Getter
@Setter
public class RegistrationReviewRequest {

    /**
     * Optional for approval, and the place a decline reason goes. Bounded to match
     * the column so an oversized note fails validation rather than the database.
     */
    @Size(max = 1000, message = "Napomena može imati najviše 1000 karaktera")
    private String reviewNote;
}
