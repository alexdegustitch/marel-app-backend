package com.aleksandarparipovic.marel_app.production_order_progress;

import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * The shape of {@code production_orders.closed_progress_snapshot} (V78, SPEC
 * odluka 16): a small envelope — {@code schemaVersion}, {@code calculatedAt} —
 * around the whole {@link OrderProgress}, so a later shape can tell this one
 * apart and the stored figure says when it was true.
 *
 * <p>Reading is deliberately forgiving: a snapshot an older build wrote, or a
 * newer one with fields this build does not know, parses with the unknown
 * parts ignored; one that cannot parse at all returns null and the caller
 * recomputes — wrong-but-silent history is the one thing this must never
 * produce.
 */
@Component
@Slf4j
public class ClosedOrderProgressStore {

    static final int SCHEMA_VERSION = 1;

    /*
     * Its own mapper, not an injected one: this application context exposes no
     * ObjectMapper bean, and the envelope needs none of the web stack's
     * modules — OrderProgress is numbers, strings and lists all the way down
     * (calculatedAt travels as a plain ISO string for the same reason).
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** The envelope to write, in the SAME transaction as the closing status. */
    public JsonNode envelope(OrderProgress progress) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", SCHEMA_VERSION);
        node.put("calculatedAt", OffsetDateTime.now().toString());
        node.set("progress", mapper.valueToTree(progress));
        return node;
    }

    /** The stored progress, or null when the envelope cannot be read back. */
    public OrderProgress read(JsonNode envelope) {
        if (envelope == null || envelope.get("progress") == null) {
            return null;
        }
        try {
            return mapper.treeToValue(envelope.get("progress"), OrderProgress.class);
        } catch (Exception e) {
            log.warn("closed_progress_snapshot se ne čita — računam ispočetka: {}", e.getMessage());
            return null;
        }
    }
}
