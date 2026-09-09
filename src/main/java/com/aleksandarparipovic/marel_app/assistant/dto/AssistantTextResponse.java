package com.aleksandarparipovic.marel_app.assistant.dto;

/**
 * The only success shape this endpoint returns: a short Serbian paragraph.
 */
public record AssistantTextResponse(String text) {
}
