package com.aleksandarparipovic.marel_app.production_order_progress.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * How far a whole production order has got.
 *
 * <p>Two different questions are answered here, and they disagree on purpose:
 *
 * <ul>
 *   <li>{@code percent} — how much of the WORK is done, counted in operations:
 *       {@code Σ min(done, required) ÷ Σ required}. It moves as soon as anybody
 *       does anything.</li>
 *   <li>{@link ProductProgress#wholeProductsDone()} — how many whole products
 *       could be SHIPPED. It stays at zero until every operation of a product
 *       has been done at least once.</li>
 * </ul>
 *
 * <p>An order can be half worked and hold nothing shippable, which is exactly
 * what a dispatcher needs to see rather than one number that blurs the two.
 *
 * @param scopeDefined false when no line of the order has an agreed scope; the
 *                     percentages are then null rather than zero, because "we
 *                     have not decided what this order needs" is not "nothing
 *                     has been done"
 */
public record OrderProgress(
        Long orderId,
        boolean scopeDefined,
        int linesWithScope,
        int linesWithoutScope,
        /** Σ required over every operation the scope asks for. */
        long requiredPieces,
        /** Σ min(done, required) — the pieces that count. */
        long donePieces,
        /** Everything recorded, including anything beyond what was asked. */
        long recordedPieces,
        long scrapPieces,
        /** donePieces ÷ requiredPieces, or null when there is no scope. */
        BigDecimal percent,
        List<ProductProgress> products,
        List<OutOfScopeWork> outOfScope
) {

    /** Nothing decided and nothing to measure against. */
    public static OrderProgress withoutScope(Long orderId, int linesWithoutScope) {
        return new OrderProgress(orderId, false, 0, linesWithoutScope,
                0, 0, 0, 0, null, List.of(), List.of());
    }
}
