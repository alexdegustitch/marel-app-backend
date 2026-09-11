package com.aleksandarparipovic.marel_app.order_reminder;

import com.aleksandarparipovic.marel_app.outbox.OutboxAggregateType;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventPublisher;
import com.aleksandarparipovic.marel_app.outbox.OutboxEventType;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;
import com.aleksandarparipovic.marel_app.production_order.repository.ProductionOrderRepository;
import com.aleksandarparipovic.marel_app.production_order_deadline.ProductionOrderDeadline;
import com.aleksandarparipovic.marel_app.production_order_deadline.repository.ProductionOrderDeadlineRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_line_item.repository.ProductionOrderLineItemRepository;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.ProductionOrderLineItemQuantity;
import com.aleksandarparipovic.marel_app.production_order_line_item_quantity.repository.ProductionOrderLineItemQuantityRepository;
import com.aleksandarparipovic.marel_app.production_order_progress.OrderProgressService;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrder;
import com.aleksandarparipovic.marel_app.sample_order.SampleOrderStatus;
import com.aleksandarparipovic.marel_app.sample_order.repository.SampleOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the 09:00 job actually does, one order per transaction.
 *
 * <p>Two duties, both about telling people in time rather than after the fact:
 * a deadline 7, 3 or 0 days away is announced into the order's conversation,
 * and an order whose work has reached 100% announces that it is ready. Both
 * are per-order transactions so one broken order cannot silence the rest of
 * the morning's mail — the job catches and moves on.
 *
 * <p>"Already said so" lives in {@code order_reminders} (see V51): a rerun
 * finds its rows and sends nothing. A threshold is due when the deadline is AT
 * OR WITHIN it and that threshold has not warned for that date — so a deadline
 * created or moved to 2 days away still warns tomorrow morning instead of
 * silently skipping past its 7- and 3-day marks.
 */
@Service
@RequiredArgsConstructor
public class OrderReminderService {

    private static final int[] THRESHOLD_DAYS = {7, 3, 0};

    /** Serbian date order, the one used everywhere else people read dates here. */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderDeadlineRepository deadlineRepository;
    private final ProductionOrderLineItemRepository lineItemRepository;
    private final ProductionOrderLineItemQuantityRepository lineItemQuantityRepository;
    private final SampleOrderRepository sampleOrderRepository;
    private final OrderReminderRepository reminderRepository;
    private final OrderProgressService orderProgressService;
    private final OutboxEventPublisher outboxEventPublisher;

    @Transactional(readOnly = true)
    public List<Long> openProductionOrderIds() {
        return productionOrderRepository.findIdsByStatusAndActive(ProductionOrderStatus.CREATED);
    }

    @Transactional(readOnly = true)
    public List<Long> openSampleOrderIds() {
        return sampleOrderRepository.findOpenIds();
    }

    /**
     * One production order's deadline sweep: every successive delivery line
     * and every line-item partial quantity that carries a date. The order's
     * own {@code delivery_deadline} is free text and is deliberately not
     * parsed — a reminder that guesses at "početak septembra" would sooner or
     * later warn about the wrong day, which is worse than not warning.
     */
    @Transactional
    public void remindProductionOrder(Long orderId, LocalDate today) {
        ProductionOrder order = productionOrderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != ProductionOrderStatus.CREATED
                || !Boolean.TRUE.equals(order.getIsActive())) {
            return;
        }

        List<String> lines = new ArrayList<>();
        List<OrderReminder> toLog = new ArrayList<>();

        for (ProductionOrderDeadline deadline :
                deadlineRepository.findAllByProductionOrder_IdAndIsActiveIsTrue(orderId)) {
            if (deadline.getDeadlineDateTo() == null) {
                continue;
            }
            String label = deadline.getQuantity() == null
                    ? "isporuka"
                    : "isporuka " + deadline.getQuantity() + " kom";
            collectDue(OrderReminder.PRODUCTION, orderId, "DEADLINE",
                    deadline.getDeadlineDateTo(), label, today, lines, toLog);
        }

        for (ProductionOrderLineItem item : lineItemRepository
                .findByProductionOrder_IdAndIsActiveIsTrueOrderByLineOrderAsc(orderId)) {
            String product = item.getProduct() == null
                    ? "-" : item.getProduct().getProductName();
            String subjectKey = "ITEM:" + (item.getProduct() == null
                    ? "-" : item.getProduct().getId());

            for (ProductionOrderLineItemQuantity quantity : lineItemQuantityRepository
                    .findByProductionOrderLineItem_IdOrderByOrderQuantityAsc(item.getId())) {
                if (!Boolean.TRUE.equals(quantity.getIsActive())
                        || quantity.getDeliveryDeadline() == null) {
                    continue;
                }
                String label = "stavka " + product
                        + (quantity.getQuantity() == null
                                ? "" : " (" + quantity.getQuantity() + " kom)");
                collectDue(OrderReminder.PRODUCTION, orderId, subjectKey,
                        quantity.getDeliveryDeadline(), label, today, lines, toLog);
            }
        }

        if (lines.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("responsibleUserId",
                order.getUser() == null ? null : order.getUser().getId());
        payload.put("deadlines", lines);

        outboxEventPublisher.publish(
                OutboxEventType.PRODUCTION_ORDER_DEADLINE_APPROACHING,
                OutboxAggregateType.PRODUCTION_ORDER,
                orderId,
                payload
        );
        reminderRepository.saveAll(toLog);
    }

    /** The sample order's single rok, on the same thresholds. */
    @Transactional
    public void remindSampleOrder(Long orderId, LocalDate today) {
        SampleOrder order = sampleOrderRepository.findById(orderId).orElse(null);
        if (order == null || SampleOrderStatus.isClosed(order.getStatus())
                || SampleOrderStatus.isCancelled(order.getStatus())
                || !Boolean.TRUE.equals(order.getIsActive())
                || order.getDeadlineDate() == null) {
            return;
        }

        List<String> lines = new ArrayList<>();
        List<OrderReminder> toLog = new ArrayList<>();
        collectDue(OrderReminder.SAMPLE, orderId, "ORDER",
                order.getDeadlineDate(), "rok naloga", today, lines, toLog);

        if (lines.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("responsibleUserId",
                order.getUser() == null ? null : order.getUser().getId());
        payload.put("deadlines", lines);

        outboxEventPublisher.publish(
                OutboxEventType.SAMPLE_ORDER_DEADLINE_APPROACHING,
                OutboxAggregateType.SAMPLE_ORDER,
                orderId,
                payload
        );
        reminderRepository.saveAll(toLog);
    }

    /**
     * The once-per-order "everything is made" notice.
     *
     * <p>Progress is computed at read time and this is the only place that
     * OBSERVES it crossing 100 — the flag on the order is what turns a value
     * that can be recomputed any number of times into an announcement made
     * exactly once. An order with no agreed razrada has a null percent and is
     * skipped: "we don't know" is not "done".
     */
    @Transactional
    public void notifyProductionOrderComplete(Long orderId) {
        ProductionOrder order = productionOrderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != ProductionOrderStatus.CREATED
                || !Boolean.TRUE.equals(order.getIsActive())
                || order.getCompletionNotifiedAt() != null) {
            return;
        }

        OrderProgress progress = orderProgressService.forOrder(orderId);
        BigDecimal percent = progress == null ? null : progress.percent();
        if (percent == null || percent.compareTo(HUNDRED) < 0) {
            return;
        }

        order.setCompletionNotifiedAt(OffsetDateTime.now());
        productionOrderRepository.save(order);

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderCode", order.getCode());
        payload.put("orderName", order.getName());
        payload.put("responsibleUserId",
                order.getUser() == null ? null : order.getUser().getId());

        outboxEventPublisher.publish(
                OutboxEventType.PRODUCTION_ORDER_READY_FOR_DELIVERY,
                OutboxAggregateType.PRODUCTION_ORDER,
                orderId,
                payload
        );
    }

    /**
     * Adds the deadline's line when any threshold is newly due, and the log
     * rows that will keep tomorrow's run quiet about it.
     *
     * <p>All satisfied-but-unlogged thresholds are logged in one go: a
     * deadline that arrives already 2 days out warns ONCE, not once for the
     * missed 7-day mark and again for the missed 3-day mark.
     */
    private void collectDue(
            String orderType, Long orderId, String subjectKey, LocalDate deadline,
            String label, LocalDate today, List<String> lines, List<OrderReminder> toLog
    ) {
        long daysUntil = ChronoUnit.DAYS.between(today, deadline);
        // Yesterday's deadline is not a reminder any more; past 7 days out,
        // nothing has to be said yet.
        if (daysUntil < 0 || daysUntil > 7) {
            return;
        }

        boolean newlyDue = false;
        for (int threshold : THRESHOLD_DAYS) {
            if (daysUntil > threshold) {
                continue;
            }
            boolean alreadySent = reminderRepository
                    .existsByOrderTypeAndOrderIdAndSubjectKeyAndDeadlineDateAndThresholdDays(
                            orderType, orderId, subjectKey, deadline, threshold);
            if (alreadySent) {
                continue;
            }
            newlyDue = true;
            toLog.add(OrderReminder.builder()
                    .orderType(orderType)
                    .orderId(orderId)
                    .subjectKey(subjectKey)
                    .deadlineDate(deadline)
                    .thresholdDays(threshold)
                    .sentAt(OffsetDateTime.now())
                    .build());
        }

        if (newlyDue) {
            lines.add(label + " — rok " + DATE_FORMAT.format(deadline)
                    + " (" + expiryPhrase(daysUntil) + ")");
        }
    }

    private static String expiryPhrase(long daysUntil) {
        if (daysUntil == 0) {
            return "ističe danas";
        }
        if (daysUntil == 1) {
            return "ističe sutra";
        }
        return "ističe za " + daysUntil + " dana";
    }
}
