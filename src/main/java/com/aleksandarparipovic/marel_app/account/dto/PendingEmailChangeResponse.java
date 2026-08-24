package com.aleksandarparipovic.marel_app.account.dto;

import java.time.OffsetDateTime;

/**
 * A change waiting to be confirmed, as the screen shows it.
 *
 * <p>Carries the new address — the person needs to see which mailbox to open, and
 * it is their own address, so there is nothing withheld by showing it. It does
 * NOT carry the code: the code exists only in the mail and as a hash in the
 * database, and an endpoint that returned it would make the whole exchange
 * pointless.
 *
 * @param attemptsLeft so the screen can warn before the last try, rather than
 *                     letting somebody discover the limit by hitting it
 */
public record PendingEmailChangeResponse(
        String newEmail,
        OffsetDateTime expiresAt,
        int attemptsLeft
) {
}
