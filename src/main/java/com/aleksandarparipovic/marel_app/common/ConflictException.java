package com.aleksandarparipovic.marel_app.common;

/**
 * A request that is valid in isolation but not against the current state of the
 * record — a forbidden status transition, or an attempt to review something
 * somebody else already reviewed.
 *
 * <p>Mapped to HTTP 409 so a client can distinguish "you sent nonsense" (400)
 * from "you lost a race" (409), which is retryable after a reload.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
