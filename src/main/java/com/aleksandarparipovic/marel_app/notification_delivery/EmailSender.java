package com.aleksandarparipovic.marel_app.notification_delivery;

/**
 * The outbound email port.
 *
 * <p>The delivery pipeline is provider-agnostic behind this interface: wiring a
 * different provider means adding one adapter bean and nothing else in the
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
    void send(EmailMessage message);
}
