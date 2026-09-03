package com.aleksandarparipovic.marel_app.production_order_progress;

import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgress;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OrderProgressSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * How much of a production order is done.
 *
 * <p>Under {@code /api/production-orders}, so both endpoints already fall under
 * the {@code PRODUCTION_ORDER_VIEW} rule the security configuration states for
 * every GET on that path. Nothing here decides who may read it.
 */
@RestController
@RequestMapping("/api/production-orders")
@RequiredArgsConstructor
public class OrderProgressController {

    /**
     * As many orders as a list screen shows at once. Generous for a page of
     * cards and small enough that the query string stays a query string.
     */
    private static final int MAX_IDS = 200;

    private final OrderProgressService service;

    /**
     * GET /api/production-orders/{id}/progress — one order, broken down by
     * product and operation, with the work recorded outside its agreed scope.
     */
    @GetMapping("/{id}/progress")
    public ResponseEntity<OrderProgress> forOrder(@PathVariable Long id) {
        return ResponseEntity.ok(service.forOrder(id));
    }

    /**
     * GET /api/production-orders/progress?ids=1,2,3 — the one-line figure for a
     * page of orders, in one request rather than one per card.
     *
     * <p>An order with no agreed scope is still in the answer, saying so. A
     * screen must be able to tell "nothing decided yet" from "nothing done",
     * and a missing entry would say neither.
     */
    @GetMapping("/progress")
    public ResponseEntity<Map<Long, OrderProgressSummary>> forOrders(@RequestParam List<Long> ids) {
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException(
                    "Najviše " + MAX_IDS + " naloga po zahtevu, traženo je " + ids.size() + ".");
        }
        return ResponseEntity.ok(service.summaries(ids));
    }
}
