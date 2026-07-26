package com.aleksandarparipovic.marel_app.user_registration_request.dto;

import com.aleksandarparipovic.marel_app.user_registration_request.UserRegistrationRequestStatus;

import java.time.OffsetDateTime;

/**
 * Read model for a registration request. Deliberately exposes no password hash,
 * no token and no session detail — only what an administrator needs to decide.
 */
public record RegistrationRequestResponse(
        Long id,
        Long userId,
        String username,
        String fullName,
        String emailAddress,
        String mobilePhone,
        String roleName,
        UserRegistrationRequestStatus status,
        String reviewNote,
        Long reviewedByUserId,
        String reviewedByName,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt
) {
}
