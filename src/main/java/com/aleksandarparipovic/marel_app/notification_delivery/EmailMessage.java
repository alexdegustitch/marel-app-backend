package com.aleksandarparipovic.marel_app.notification_delivery;

import java.util.List;

/**
 * One outbound e-mail, fully resolved.
 *
 * <p>The sender address is deliberately NOT here: it is infrastructure, fixed per
 * installation, and the adapter reads it from configuration. What varies per
 * message is who it should look like it came from and where a reply belongs —
 * both of which follow the person whose action caused the notification.
 *
 * @param toAddresses everyone this single message is addressed to. A list rather
 *                    than one address because an order's colleagues must all
 *                    receive the SAME message: identical Message-ID, so a Reply
 *                    All from any of them lands in everybody's thread. Sending
 *                    them separate copies would give each a private chain that
 *                    the first reply splits apart.
 * @param fromName    display name shown in the recipient's inbox — the acting
 *                    employee's name, or the application name for system events
 * @param replyTo     where a reply goes; null sends replies to the from address
 * @param messageId   this message's RFC 5322 Message-ID, assigned when the
 *                    delivery row was created so a retry re-sends an identical
 *                    message the client discards as a duplicate. Null lets the
 *                    provider assign one, which is correct for mail that belongs
 *                    to no conversation.
 * @param inReplyTo   the message this one continues; null starts a conversation
 * @param references  the full ancestor chain, space separated; null or blank on
 *                    a first message
 */
public record EmailMessage(
        List<String> toAddresses,
        String subject,
        String htmlBody,
        String fromName,
        String replyTo,
        String messageId,
        String inReplyTo,
        String references
) {

    /**
     * A standalone message to one person — no conversation to join.
     *
     * <p>For mail that is genuinely on its own: a verification code, a notice that
     * an address was changed. Leaving the threading headers null lets the provider
     * assign a Message-ID, which is the right answer when there is no chain to
     * keep consistent with.
     */
    public static EmailMessage to(
            String address, String subject, String htmlBody, String fromName, String replyTo
    ) {
        return new EmailMessage(
                List.of(address), subject, htmlBody, fromName, replyTo, null, null, null);
    }
}
