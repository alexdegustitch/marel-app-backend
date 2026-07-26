package com.aleksandarparipovic.marel_app.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Converts between the JSON representations used at the two ends of this
 * application.
 *
 * <p>Spring Boot 4 ships <b>Jackson 3</b> ({@code tools.jackson}) as the HTTP
 * message converter, while this project's JSONB columns are mapped with
 * <b>Jackson 2</b> nodes ({@code com.fasterxml.jackson.databind.JsonNode} — see
 * {@code AuditLog}). A Jackson 2 {@code JsonNode} on a request or response DTO
 * therefore fails to bind at runtime with a {@code HttpMessageConversionException}.
 *
 * <p>The rule this class enforces: <b>DTOs expose plain {@code Map}/{@code List},
 * entities keep Jackson 2 nodes,</b> and conversion happens here. Never put a
 * {@code JsonNode} of either generation on a DTO.
 */
public final class JsonPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPayloads() {
    }

    public static JsonNode toNode(Object value) {
        return value == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(value);
    }

    public static JsonNode emptyObject() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode emptyArray() {
        return MAPPER.createArrayNode();
    }

    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    public static List<Object> toList(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        return MAPPER.convertValue(node, new TypeReference<List<Object>>() {
        });
    }

    /** Serialized byte size, for the same limits the database check constraints apply. */
    public static int byteSize(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
