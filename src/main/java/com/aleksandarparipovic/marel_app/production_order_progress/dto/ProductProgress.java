package com.aleksandarparipovic.marel_app.production_order_progress.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * How far one product of an order has got, counted in WHOLE products.
 *
 * <p>A product is finished when every operation the scope asks for has been done
 * for it, so the count is the smallest number of assemblies any one of those
 * operations can account for. Doing four of an operation that is needed twice
 * per assembly is two products, not four; doing none of another operation is
 * none, whatever the rest say.
 *
 * <p>Keyed by product rather than by order line: work is recorded against an
 * order and an operation, and an operation belongs to a product, so that is the
 * finest grain the data actually distinguishes. Where a product appears on one
 * line — every order in the database today — the two are the same thing, and
 * {@code lineItemIds} says which lines are covered either way.
 */
public record ProductProgress(
        Long productId,
        String productName,
        List<Long> lineItemIds,
        /** Σ of the covered lines' ordered quantities. */
        long requiredProducts,
        /**
         * The LOWER bound of the finished count: pieces that are whole in every
         * reading of the shared pools. With one line, or with no shared
         * operations, it equals {@link #wholeProductsDoneMax} and the range
         * collapses to the old single figure.
         */
        long wholeProductsDone,
        /**
         * The UPPER bound: the most whole products any allowed reading of the
         * shared pools adds up to, computed JOINTLY over the lines (SPEC 5.6,
         * mera 2) — not the sum of each line's own best case, which would count
         * a shared pool twice.
         */
        long wholeProductsDoneMax,
        /** wholeProductsDone ÷ requiredProducts, never above 100. */
        BigDecimal percent,
        /** wholeProductsDoneMax ÷ requiredProducts — the range's other end. */
        BigDecimal percentMax,
        /**
         * Which operation is holding the product back, or null when it is
         * finished. The id travels with the name so a screen can link to the
         * operation rather than only naming it.
         */
        Long bottleneckOperationId,
        String bottleneckOperationName,
        List<OperationProgress> operations,
        /** The per-line ranges behind the total; one row per order line. */
        List<LineProgress> lines
) {
}
