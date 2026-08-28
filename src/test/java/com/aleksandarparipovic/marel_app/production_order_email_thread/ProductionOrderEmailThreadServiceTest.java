package com.aleksandarparipovic.marel_app.production_order_email_thread;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The headers that decide whether five mails about one order read as a
 * conversation or as five unrelated notifications.
 *
 * <p>None of this is visible in the application: a wrong In-Reply-To still
 * produces a mail that sends, delivers, and simply lands outside its thread. The
 * only place it can be caught before a customer sees it is here.
 */
class ProductionOrderEmailThreadServiceTest {

    private ProductionOrderEmailThreadService service;
    private Map<Long, ProductionOrderEmailThread> stored;

    @BeforeEach
    void setUp() {
        stored = new HashMap<>();

        ProductionOrderEmailThreadRepository repository =
                mock(ProductionOrderEmailThreadRepository.class);

        when(repository.findByProductionOrder_Id(any()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));

        when(repository.save(any(ProductionOrderEmailThread.class))).thenAnswer(call -> {
            ProductionOrderEmailThread thread = call.getArgument(0);
            stored.put(thread.getProductionOrder().getId(), thread);
            return thread;
        });

        service = new ProductionOrderEmailThreadService(repository, "no-reply@dooklytics.com");
    }

    private static ProductionOrder order(Long id, String code, String name) {
        ProductionOrder order = new ProductionOrder();
        order.setId(id);
        order.setCode(code);
        order.setName(name);
        return order;
    }

    @Test
    @DisplayName("the first mail opens the conversation and replies to nothing")
    void firstMessageStartsTheThread() {
        var headers = service.nextMessage(order(42L, "N-12", "Kućišta"));

        assertThat(headers.subject()).isEqualTo("Nalog N-12 — Kućišta");
        assertThat(headers.messageId()).isEqualTo("<po-42-1@dooklytics.com>");
        // Nothing exists yet to reply to; a client seeing In-Reply-To here would
        // be looking for a message that was never sent.
        assertThat(headers.inReplyTo()).isNull();
        assertThat(headers.references()).isNull();
    }

    @Test
    @DisplayName("the second mail replies to the first and keeps its subject")
    void secondMessageContinuesTheThread() {
        ProductionOrder order = order(42L, "N-12", "Kućišta");
        var first = service.nextMessage(order);
        var second = service.nextMessage(order);

        assertThat(second.inReplyTo()).isEqualTo(first.messageId());
        assertThat(second.references()).isEqualTo(first.messageId());
        assertThat(second.messageId()).isEqualTo("<po-42-2@dooklytics.com>");
        // Exactly one "Re:", and the same words after it. A subject that drifts
        // splits the conversation even when the References chain is perfect.
        assertThat(second.subject()).isEqualTo("Re: Nalog N-12 — Kućišta");
    }

    @Test
    @DisplayName("References accumulates, so the chain survives a human replying mid-thread")
    void referencesChainGrows() {
        ProductionOrder order = order(7L, "N-99", "Nosači");
        service.nextMessage(order);
        service.nextMessage(order);
        var third = service.nextMessage(order);

        assertThat(third.references())
                .isEqualTo("<po-7-1@dooklytics.com> <po-7-2@dooklytics.com>");
        assertThat(third.inReplyTo()).isEqualTo("<po-7-2@dooklytics.com>");
    }

    @Test
    @DisplayName("every message gets its own id — a repeat would be discarded as a duplicate")
    void idsAreNeverReused() {
        ProductionOrder order = order(3L, "N-1", "Test");

        assertThat(java.util.stream.Stream
                .of(service.nextMessage(order), service.nextMessage(order),
                        service.nextMessage(order))
                .map(ProductionOrderEmailThreadService.ThreadHeaders::messageId)
                .distinct()
                .count())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("two orders are two separate conversations")
    void ordersDoNotShareAThread() {
        var a = service.nextMessage(order(1L, "N-A", "Prvi"));
        var b = service.nextMessage(order(2L, "N-B", "Drugi"));

        assertThat(b.inReplyTo()).isNull();
        assertThat(b.messageId()).isNotEqualTo(a.messageId());
    }

    @Test
    @DisplayName("an order with no name still gets a usable subject")
    void subjectSurvivesAMissingName() {
        assertThat(service.nextMessage(order(5L, "N-77", null)).subject())
                .isEqualTo("Nalog N-77");
    }
}
