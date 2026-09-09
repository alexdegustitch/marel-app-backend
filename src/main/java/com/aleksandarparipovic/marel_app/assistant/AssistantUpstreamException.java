package com.aleksandarparipovic.marel_app.assistant;

/**
 * Thrown when the upstream model provider fails or returns an unusable body.
 * The controller maps it to a 502 with a plain Serbian message; the failing
 * status is logged, never the API key.
 */
public class AssistantUpstreamException extends RuntimeException {

    public AssistantUpstreamException(String message) {
        super(message);
    }

    public AssistantUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
