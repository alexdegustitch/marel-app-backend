package com.aleksandarparipovic.marel_app.auth.google;

/**
 * What Google tells us about the person who just signed in there.
 *
 * @param subject       Google's {@code sub} — the one identifier Google documents
 *                      as stable for the life of the account. The address can be
 *                      renamed by its owner; this cannot, which is why a joined
 *                      profile is recorded by it.
 * @param email         the address, which is what a person recognises
 * @param emailVerified whether Google itself vouches for that address
 */
public record GoogleUserInfo(
        String subject,
        String email,
        boolean emailVerified,
        String givenName,
        String familyName
) {
}
