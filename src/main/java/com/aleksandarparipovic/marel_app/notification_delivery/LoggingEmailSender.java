package com.aleksandarparipovic.marel_app.notification_delivery;

import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link EmailSender} used until a real provider is configured.
 *
 * <p>Logs the recipient and subject and reports success, so the delivery pipeline
 * can be exercised end to end without sending anything. It deliberately does NOT
 * log the body: message text can carry business detail that has no place in
 * application logs.
 *
 * <p>Registered conditionally by {@link NotificationDeliveryConfig} — defining any
 * other EmailSender bean replaces it, with no configuration flag to remember.
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String toAddress, String subject, String body) {
        log.info("[EmailSender] No mail provider configured — would send to {} with subject '{}'",
                toAddress, subject);
    }
}
