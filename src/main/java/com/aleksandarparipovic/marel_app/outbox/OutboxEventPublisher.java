package com.aleksandarparipovic.marel_app.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Records a business event for later delivery.
 *
 * <p>MANDATORY propagation is the whole point: publishing must join the caller's
 * transaction, never start its own. If this ever ran in a separate transaction,
 * an event could be committed for a business change that later rolled back —
 * users would be notified about something that never happened.
 */
@Service
@RequiredArgsConstructor
public class OutboxEventPublisher {

    /**
     * Deliberately NOT the auto-configured ObjectMapper bean.
     *
     * <p>Spring Boot 4 auto-configures a Jackson 3 mapper ({@code tools.jackson}),
     * but this project's JSONB columns are mapped with Jackson 2 nodes
     * ({@code com.fasterxml.jackson.databind.JsonNode} — see {@code AuditLog}).
     * Injecting the bean would fail at startup, and mixing the two Jackson
     * generations here would be worse than a local mapper: converting a plain
     * {@code Map} to a tree needs no application-level serialization config, so a
     * private instance is both correct and side-effect free.
     */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    private final OutboxEventRepository outboxEventRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(
            OutboxEventType eventType,
            OutboxAggregateType aggregateType,
            Long aggregateId,
            Map<String, Object> payload
    ) {
        OutboxEvent event = OutboxEvent.builder()
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .payload(PAYLOAD_MAPPER.valueToTree(payload == null ? Map.of() : payload))
                .status(OutboxEventStatus.PENDING)
                .build();

        return outboxEventRepository.save(event);
    }
}
