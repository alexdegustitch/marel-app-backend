package com.aleksandarparipovic.marel_app.common;

/**
 * Turns an exception into something safe to persist in a {@code last_error} column.
 *
 * <p>Retry state is operational data that anyone with access to the outbox or
 * delivery views can read, so it must never carry stack traces, provider payloads,
 * credentials, tokens or personal data. Only the exception type and its message
 * survive, truncated to the column width.
 */
public final class ErrorSanitizer {

    /** Matches the last_error column width on outbox_events and notification_deliveries. */
    public static final int MAX_LENGTH = 1000;

    private ErrorSanitizer() {
    }

    public static String sanitize(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        String combined = throwable.getClass().getSimpleName() + ": " + message;

        return combined.length() <= MAX_LENGTH
                ? combined
                : combined.substring(0, MAX_LENGTH);
    }
}
