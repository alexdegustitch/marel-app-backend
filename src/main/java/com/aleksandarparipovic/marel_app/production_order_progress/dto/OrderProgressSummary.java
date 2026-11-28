package com.aleksandarparipovic.marel_app.production_order_progress.dto;

import java.math.BigDecimal;

/**
 * The one-line version, for a list of orders: enough to draw a bar and say what
 * it means, without the per-operation breakdown a card has no room for.
 */
public record OrderProgressSummary(
        Long orderId,
        boolean scopeDefined,
        BigDecimal percent,
        long requiredPieces,
        long donePieces,
        /** Whole products SURELY ready across the order — the range's floor. */
        long wholeProductsDone,
        /** The range's ceiling: what the most favourable reading adds up to (SPEC 5.6). */
        long wholeProductsDoneMax,
        long requiredProducts
) {

    public static OrderProgressSummary withoutScope(Long orderId) {
        return new OrderProgressSummary(orderId, false, null, 0, 0, 0, 0, 0);
    }

    public static OrderProgressSummary of(OrderProgress progress) {
        long whole = progress.products().stream().mapToLong(ProductProgress::wholeProductsDone).sum();
        long wholeMax = progress.products().stream().mapToLong(ProductProgress::wholeProductsDoneMax).sum();
        long required = progress.products().stream().mapToLong(ProductProgress::requiredProducts).sum();
        return new OrderProgressSummary(
                progress.orderId(),
                progress.scopeDefined(),
                progress.percent(),
                progress.requiredPieces(),
                progress.donePieces(),
                whole,
                wholeMax,
                required);
    }
}
