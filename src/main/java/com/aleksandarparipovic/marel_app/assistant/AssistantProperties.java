package com.aleksandarparipovic.marel_app.assistant;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for the "Spiky" AI assistant.
 *
 * <p>The API key is read from the {@code OPENAI_API_KEY} environment variable
 * only. It is deliberately NOT bound under a {@code assistant.*} property so it
 * cannot accidentally land in application.properties, and its value is NEVER
 * logged anywhere in this package. Model and base URL have safe defaults in
 * application.properties and may be overridden by env/properties.
 */
@Getter
@Component
public class AssistantProperties {

    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public AssistantProperties(
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${assistant.model:gpt-5.6-luna}") String model,
            @Value("${assistant.base-url:https://api.openai.com/v1}") String baseUrl
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    /**
     * The feature is ON only when an API key is present. With no key the
     * controller answers 503 and no model call is ever attempted.
     */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
