package com.aleksandarparipovic.marel_app.notification_delivery;

/**
 * The outbound email port.
 *
 * <p>This project has no mail infrastructure — there is no spring-boot-starter-mail
 * dependency and no SMTP configuration. Rather than add one speculatively, the
 * delivery pipeline is complete and provider-agnostic behind this interface.
 * Wiring a real provider later means adding one adapter bean; nothing else in the
 * pipeline changes.
 *
 * <p>Implementations are called OUTSIDE any database transaction, so a slow
 * provider can never hold a connection open.
 */
public interface EmailSender {

    /**
     * @throws RuntimeException on failure; the worker records a sanitized message
     *                          and schedules a retry.
     */
    void send(String toAddress, String subject, String body);
}
