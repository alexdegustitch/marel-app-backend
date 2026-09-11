package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.order_reminder.OrderReminderRepository;
import com.aleksandarparipovic.marel_app.order_reminder.OrderReminderService;
import com.aleksandarparipovic.marel_app.outbox.OutboxEvent;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventRepository;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.product.Product;
import com.aleksandarparipovic.marel_app.product.repository.ProductRepository;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_deadline.ProductionOrderDeadline;
import com.aleksandarparipovic.marel_app.production_order_deadline.repository.ProductionOrderDeadlineRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.ProductionOrderLineItemQuantity;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.repository.ProductionOrderLineItemQuantityRepository;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrderStatus;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
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
 * The morning reminder sweep, pinned where it can lie silently: a deadline
 * warns when it is due, says WHICH deadline it is, and never warns twice for
 * the same (deadline, date, threshold) — while a moved deadline warns again,
 * because the new date is a new promise.
 */
@Transactional
class OrderReminderIT extends AbstractIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired private OrderReminderService reminderService;
    @Autowired private OrderReminderRepository reminderRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ProductionOrderRepository productionOrderRepository;
    @Autowired private ProductionOrderDeadlineRepository deadlineRepository;
    @Autowired private ProductionOrderLineItemRepository lineItemRepository;
    @Autowired private ProductionOrderLineItemQuantityRepository quantityRepository;
    @Autowired private SampleOrderRepository sampleOrderRepository;
    @Autowired private ProductRepository productRepository;

    private ProductionOrder anOpenOrder() {
        int n = COUNTER.incrementAndGet();
        return productionOrderRepository.save(ProductionOrder.builder()
                .code("ROK-" + n + "-" + System.nanoTime())
                .name("Nalog sa rokom " + n)
                .status(ProductionOrderStatus.CREATED)
                .testingRequired(false)
                .isHighPriority(false)
                .isAnnounced(false)
                .hasSuccessiveDeliveries(true)
                .isActive(true)
                .build());
    }

    private void aDeadline(ProductionOrder order, LocalDate dateTo, Integer quantity) {
        ProductionOrderDeadline deadline = new ProductionOrderDeadline();
        deadline.setProductionOrder(order);
        deadline.setDeadlineOrder(1);
        deadline.setDeadlineDateTo(dateTo);
        deadline.setQuantity(quantity);
        deadline.setIsActive(true);
        deadlineRepository.save(deadline);
    }

    private void aLineItemDue(ProductionOrder order, LocalDate due) {
        Product product = new Product();
        product.setProductName("Čaura " + COUNTER.incrementAndGet());
        product.setActive(true);
        product = productRepository.save(product);

        ProductionOrderLineItem item = new ProductionOrderLineItem();
        item.setProductionOrder(order);
        item.setProduct(product);
        item.setQuantity(500);
        item.setLineOrder(1);
        item.setIsActive(true);
        item = lineItemRepository.save(item);

        ProductionOrderLineItemQuantity quantity = new ProductionOrderLineItemQuantity();
        quantity.setProductionOrderLineItem(item);
        quantity.setOrderQuantity(1);
        quantity.setQuantity(500);
        quantity.setDeliveryDeadline(due);
        quantity.setIsActive(true);
        quantityRepository.save(quantity);
    }

    private List<OutboxEvent> eventsFor(Long orderId, OutboxEventType type) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == type && e.getAggregateId().equals(orderId))
                .toList();
    }

    @Test
    @DisplayName("one morning, one mail: every due deadline in a single event, each named")
    void dueDeadlinesWarnOnceAndNamed() {
        ProductionOrder order = anOpenOrder();
        LocalDate today = LocalDate.now();
        aDeadline(order, today.plusDays(3), 500);
        aLineItemDue(order, today);

        reminderService.remindProductionOrder(order.getId(), today);

        List<OutboxEvent> events = eventsFor(order.getId(),
                OutboxEventType.PRODUCTION_ORDER_DEADLINE_APPROACHING);
        assertThat(events).hasSize(1);

        var lines = events.get(0).getPayload().get("deadlines");
        assertThat(lines).hasSize(2);
        assertThat(lines.toString())
                .contains("isporuka 500 kom")
                .contains("ističe za 3 dana")
                .contains("stavka Čaura")
                .contains("ističe danas");

        // Every satisfied threshold is logged at once — the deadline 3 days
        // out logs its 7- and 3-day marks, the item due today all three — so
        // ONE mail goes out now and no catch-up nag follows tomorrow.
        assertThat(reminderRepository.findAll())
                .filteredOn(r -> r.getOrderId().equals(order.getId()))
                .hasSize(5);
    }

    @Test
    @DisplayName("a rerun the same morning sends nothing new")
    void rerunIsSilent() {
        ProductionOrder order = anOpenOrder();
        LocalDate today = LocalDate.now();
        aDeadline(order, today.plusDays(7), 200);

        reminderService.remindProductionOrder(order.getId(), today);
        reminderService.remindProductionOrder(order.getId(), today);

        assertThat(eventsFor(order.getId(),
                OutboxEventType.PRODUCTION_ORDER_DEADLINE_APPROACHING)).hasSize(1);
    }

    @Test
    @DisplayName("a moved deadline warns again — the new date is a new promise")
    void movedDeadlineWarnsAgain() {
        ProductionOrder order = anOpenOrder();
        LocalDate today = LocalDate.now();
        aDeadline(order, today.plusDays(7), 200);

        reminderService.remindProductionOrder(order.getId(), today);

        // The deadline is pushed out and later drifts back within range.
        ProductionOrderDeadline moved = deadlineRepository
                .findAllByProductionOrder_IdAndIsActiveIsTrue(order.getId()).get(0);
        moved.setDeadlineDateTo(today.plusDays(5));
        deadlineRepository.save(moved);

        reminderService.remindProductionOrder(order.getId(), today);

        assertThat(eventsFor(order.getId(),
                OutboxEventType.PRODUCTION_ORDER_DEADLINE_APPROACHING)).hasSize(2);
    }

    @Test
    @DisplayName("closed doors: a delivered or cancelled order gets no reminder")
    void terminalOrdersAreSkipped() {
        ProductionOrder order = anOpenOrder();
        LocalDate today = LocalDate.now();
        aDeadline(order, today, 100);
        order.setStatus(ProductionOrderStatus.DELIVERED);
        productionOrderRepository.save(order);

        reminderService.remindProductionOrder(order.getId(), today);

        assertThat(eventsFor(order.getId(),
                OutboxEventType.PRODUCTION_ORDER_DEADLINE_APPROACHING)).isEmpty();
    }

    @Test
    @DisplayName("the sample order's single rok warns at each threshold, once")
    void sampleOrderWarnsPerThreshold() {
        int n = COUNTER.incrementAndGet();
        LocalDate deadline = LocalDate.now().plusDays(7);
        SampleOrder order = sampleOrderRepository.save(SampleOrder.builder()
                .code("UZ-ROK-" + n + "-" + System.nanoTime())
                .name("Uzorci sa rokom " + n)
                .creationDate(LocalDate.now())
                .deadlineDate(deadline)
                .status(SampleOrderStatus.CREATED)
                .isActive(true)
                .build());

        reminderService.remindSampleOrder(order.getId(), deadline.minusDays(7));
        reminderService.remindSampleOrder(order.getId(), deadline.minusDays(7));
        reminderService.remindSampleOrder(order.getId(), deadline.minusDays(3));

        List<OutboxEvent> events = eventsFor(order.getId(),
                OutboxEventType.SAMPLE_ORDER_DEADLINE_APPROACHING);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getPayload().get("deadlines").toString())
                .contains("rok naloga").contains("ističe za 3 dana");
    }

    @Test
    @DisplayName("an order with no agreed razrada is never announced as done")
    void unknownProgressIsNotDone() {
        ProductionOrder order = anOpenOrder();

        reminderService.notifyProductionOrderComplete(order.getId());

        assertThat(eventsFor(order.getId(),
                OutboxEventType.PRODUCTION_ORDER_READY_FOR_DELIVERY)).isEmpty();
        assertThat(productionOrderRepository.findById(order.getId()).orElseThrow()
                .getCompletionNotifiedAt()).isNull();
    }
}
