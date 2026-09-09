package com.aleksandarparipovic.marel_app.assistant.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * One turn in a Sparky conversation: the new user {@code message} plus the prior
 * {@code history} (may be null/empty). History entries whose role is neither
 * "user" nor "assistant" are ignored by the service.
 */
public record ChatRequest(
        @NotBlank String message,
        List<Message> history
) {
    /** A prior turn — {@code role} is "user" or "assistant". */
    public record Message(String role, String content) {
    }
}
