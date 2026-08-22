package com.aleksandarparipovic.marel_app.notification_delivery;

/**
 * One outbound e-mail, fully resolved.
 *
 * <p>The sender address is deliberately NOT here: it is infrastructure, fixed per
 * installation, and the adapter reads it from configuration. What varies per
 * message is who it should look like it came from and where a reply belongs —
 * both of which follow the person whose action caused the notification.
 *
 * @param fromName display name shown in the recipient's inbox — the acting
 *                 employee's name, or the application name for system events
 * @param replyTo  where a reply goes; null sends replies to the from address
 */
public record EmailMessage(
        String toAddress,
        String subject,
        String htmlBody,
        String fromName,
        String replyTo
) {
}
