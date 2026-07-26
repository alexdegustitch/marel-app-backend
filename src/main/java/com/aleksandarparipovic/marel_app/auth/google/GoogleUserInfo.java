package com.aleksandarparipovic.marel_app.auth.google;

public record GoogleUserInfo(
        String email,
        boolean emailVerified,
        String givenName,
        String familyName
) {
}
