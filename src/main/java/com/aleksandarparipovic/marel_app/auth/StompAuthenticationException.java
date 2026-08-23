package com.aleksandarparipovic.marel_app.auth;

import org.springframework.messaging.MessagingException;

/**
 * Refuses a STOMP CONNECT. Thrown from the inbound channel interceptor, which
 * makes Spring send an ERROR frame and close the session instead of establishing
 * an anonymous one.
 */
public class StompAuthenticationException extends MessagingException {

    public StompAuthenticationException(String description) {
        super(description);
    }
}
