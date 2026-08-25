package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.outbox.OutboxEvent;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventRepository;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderService;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderCreateRequest;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderDetailDto;
import com.aleksandarparipovic.marel_app.production_order.dto.ProductionOrderUpdateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A moved delivery date is announced. Anything else about the order is not.
 *
 * <p>This is the guard the feature lives or dies by. update() replaces the whole
 * deadline set on every save — it deactivates the old rows and inserts new ones
 * even when the form never touched them — so a naive "we just wrote deadlines,
 * announce it" would e-mail the entire recipient list every time somebody fixes
 * a typo in a note. Within a month people would switch the notifications off.
 */
@Transactional
class ProductionOrderDeadlineChangeIT extends AbstractIntegrationTest {

    private static final AtomicInteger CODE = new AtomicInteger();

    @Autowired private ProductionOrderService productionOrderService;
    @Autowired private OutboxEventRepository outboxEventRepository;

    private ProductionOrderDetailDto anOrderWithDeadline(LocalDate deadline) {
        return productionOrderService.create(new ProductionOrderCreateRequest(
                "IT-DEADLINE-" + CODE.incrementAndGet(),
                "Test nalog",
                null,               // customerId — this suite is about deadlines
                "prva napomena",
                false, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), null,
                false, false, false,
                List.of(new ProductionOrderCreateRequest.DeadlineRequest(null, deadline, 10)),
                List.of()));
    }

    private ProductionOrderUpdateRequest update(String note, LocalDate deadline, Integer quantity) {
        return new ProductionOrderUpdateRequest(
                "Test nalog", null, note,
                false, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), null,
                false, false, false,
                List.of(new ProductionOrderUpdateRequest.DeadlineRequest(null, deadline, quantity)),
                List.of());
    }

    private List<OutboxEvent> deadlineEventsFor(Long orderId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == OutboxEventType.PRODUCTION_ORDER_DEADLINE_CHANGED)
                .filter(e -> orderId.equals(e.getAggregateId()))
                .toList();
    }

    @Test
    @DisplayName("editing anything but the dates announces nothing")
    void unchangedDeadlinesPublishNothing() {
        ProductionOrderDetailDto order = anOrderWithDeadline(LocalDate.of(2026, 3, 1));

        productionOrderService.update(order.id(), update("druga napomena", LocalDate.of(2026, 3, 1), 10));

        assertThat(deadlineEventsFor(order.id())).isEmpty();
    }

    @Test
    @DisplayName("a moved date is announced once, with both the old and the new value")
    void movedDeadlineIsPublished() {
        ProductionOrderDetailDto order = anOrderWithDeadline(LocalDate.of(2026, 3, 1));

        productionOrderService.update(order.id(), update("prva napomena", LocalDate.of(2026, 4, 15), 10));

        List<OutboxEvent> events = deadlineEventsFor(order.id());
        assertThat(events).hasSize(1);

        var payload = events.getFirst().getPayload();
        assertThat(payload.get("deadlinesBefore").toString()).contains("01.03.2026.");
        assertThat(payload.get("deadlinesAfter").toString()).contains("15.04.2026.");
    }

    @Test
    @DisplayName("a changed quantity on the same date is a changed deadline too")
    void changedQuantityIsPublished() {
        ProductionOrderDetailDto order = anOrderWithDeadline(LocalDate.of(2026, 3, 1));

        productionOrderService.update(order.id(), update("prva napomena", LocalDate.of(2026, 3, 1), 25));

        assertThat(deadlineEventsFor(order.id())).hasSize(1);
    }

    @Test
    @DisplayName("saving the same form twice announces the change only once")
    void repeatedIdenticalSaveIsSilentTheSecondTime() {
        ProductionOrderDetailDto order = anOrderWithDeadline(LocalDate.of(2026, 3, 1));

        productionOrderService.update(order.id(), update("prva napomena", LocalDate.of(2026, 4, 15), 10));
        productionOrderService.update(order.id(), update("prva napomena", LocalDate.of(2026, 4, 15), 10));

        assertThat(deadlineEventsFor(order.id())).hasSize(1);
    }
}
