package com.aleksandarparipovic.marel_app.account.dto;

import java.time.OffsetDateTime;

/**
 * Whether this profile is joined to a Google account, as its own owner sees it.
 *
 * <p>Served ONLY from {@code /api/me} and deliberately not added to
 * {@code UserDto}: that DTO feeds the staff directory, and a colleague's private
 * Gmail address is not a thing the directory is for. The subject is not here
 * either — it is an opaque number nobody recognises, so showing it would say
 * nothing the address does not say better.
 *
 * @param linkedAt when the owner joined the two; null when nothing is joined
 */
public record GoogleLinkStatusResponse(
        boolean linked,
        String googleEmail,
        OffsetDateTime linkedAt,
        /**
         * Whether Google sign-in is configured on this server at all. False makes
         * the screen say so plainly instead of offering a button that can only
         * fail — an installation without Google credentials is a configuration
         * state, not the person's mistake.
         */
        boolean googleConfigured,
        /**
         * Whether this account has a local password.
         *
         * <p>The screen needs it to explain why severing the link is refused: an
         * account with no password and no Google account could not be signed into
         * at all, so the password has to come first.
         */
        boolean hasPassword
) {
}
