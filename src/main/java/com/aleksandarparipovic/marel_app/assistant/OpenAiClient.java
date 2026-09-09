package com.aleksandarparipovic.marel_app.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the OpenAI-compatible Chat Completions endpoint, built on
 * Spring's {@link RestClient} — no extra SDK dependency.
 *
 * <p>The API key is attached per request as a Bearer header. It is read from
 * {@link AssistantProperties} and NEVER logged: on failure only the HTTP status
 * (or the exception type) is recorded.
 */
@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final AssistantProperties properties;
    private final RestClient restClient;

    public OpenAiClient(AssistantProperties properties) {
        this.properties = properties;
        // Built once, at the base URL; the key is added per call, not baked in.
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    /**
     * Send the system + user messages and return {@code choices[0].message.content}.
     *
     * @throws AssistantUpstreamException on any HTTP error or unusable response
     */
    @SuppressWarnings("unchecked")
    public String complete(String systemPrompt, String userMessage) {
        // GPT-5-generation models (Luna included) use `max_completion_tokens`, not
        // `max_tokens`, and reject a custom `temperature`. Reasoning tokens count
        // toward this cap, so it is set well above the ~2-4 sentence answer we want.
        // Kept minimal (model + messages + cap) so no rejected parameter can 400.
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "max_completion_tokens", 700
        );

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            // Status + the provider's error BODY (its JSON explains a 400). The
            // body is OpenAI's own message, never our key or request headers.
            log.warn("Assistant upstream returned HTTP {} — body: {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw new AssistantUpstreamException("Upstream model error: " + ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            log.warn("Assistant upstream call failed: {}", ex.getClass().getSimpleName());
            throw new AssistantUpstreamException("Upstream model call failed", ex);
        }

        String content = extractContent(response);
        if (content == null || content.isBlank()) {
            log.warn("Assistant upstream returned an empty completion");
            throw new AssistantUpstreamException("Empty completion");
        }
        return content.strip();
    }

    /**
     * A single Chat Completions turn that MAY use function-calling tools.
     *
     * <p>Returns {@code choices[0].message} verbatim as a Map — it carries the
     * assistant's {@code content} (possibly null) and, when the model wants to
     * call a tool, a {@code tool_calls} array. The caller (SparkyService) drives
     * the tool loop: it feeds the tool results back and calls this again.
     *
     * <p>Pass {@code tools == null} (or empty) to force a plain answer — the model
     * then has no tool to call and must reply with content.
     *
     * @throws AssistantUpstreamException on any HTTP error or unusable response
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chatWithTools(List<Map<String, Object>> messages, List<Object> tools) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            // gpt-5.6-luna is a reasoning model; Chat Completions rejects function
            // tools unless reasoning is turned off (or one uses /v1/responses).
            body.put("reasoning_effort", "none");
        }
        // GPT-5-generation models use `max_completion_tokens`, not `max_tokens`. Reasoning +
        // tool-call arguments + the final answer all count toward this, so it is set generously.
        body.put("max_completion_tokens", 800);

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            // Status + the provider's error BODY (its JSON explains a 400). The
            // body is OpenAI's own message, never our key or request headers.
            log.warn("Assistant upstream returned HTTP {} — body: {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw new AssistantUpstreamException("Upstream model error: " + ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            log.warn("Assistant upstream call failed: {}", ex.getClass().getSimpleName());
            throw new AssistantUpstreamException("Upstream model call failed", ex);
        }

        Map<String, Object> message = firstChoiceMessage(response);
        if (message == null) {
            log.warn("Assistant upstream returned no message");
            throw new AssistantUpstreamException("Empty completion");
        }
        return message;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstChoiceMessage(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        if (!(choices.get(0) instanceof Map<?, ?> firstChoice)) {
            return null;
        }
        if (!(firstChoice.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        return (Map<String, Object>) message;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        if (!(choices.get(0) instanceof Map<?, ?> firstChoice)) {
            return null;
        }
        if (!(firstChoice.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        Object content = message.get("content");
        return (content instanceof String s) ? s : null;
    }
}
