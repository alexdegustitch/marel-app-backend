package com.aleksandarparipovic.marel_app.notification_delivery;

import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link EmailSender} used until a real provider is configured.
 *
 * <p>Logs the recipients, subject and threading headers and reports success, so
 * the delivery pipeline can be exercised end to end without sending anything. It
 * deliberately does NOT log the body: message text can carry business detail that
 * has no place in application logs.
 *
 * <p>The headers ARE logged, because they are the part that goes wrong silently.
 * A broken In-Reply-To produces a perfectly deliverable mail that simply lands
 * outside its conversation — invisible in every log unless it is printed here.
 *
 * <p>Registered conditionally by {@link NotificationDeliveryConfig} — defining any
 * other EmailSender bean replaces it, with no configuration flag to remember.
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(EmailMessage message) {
        log.info("[EmailSender] No mail provider configured — would send to {} with subject '{}'",
                message.toAddresses(), message.subject());

        if (message.messageId() != null) {
            log.info("[EmailSender]   Message-ID: {} | In-Reply-To: {} | References: {}",
                    message.messageId(),
                    message.inReplyTo() == null ? "-" : message.inReplyTo(),
                    message.references() == null ? "-" : message.references());
        }
    }
}
