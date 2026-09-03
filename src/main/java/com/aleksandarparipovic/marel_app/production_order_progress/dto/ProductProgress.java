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
        /** The bottleneck: the fewest whole products any needed operation allows. */
        long wholeProductsDone,
        /** wholeProductsDone ÷ requiredProducts, never above 100. */
        BigDecimal percent,
        /** Which operation is holding the product back, or null when it is finished. */
        String bottleneckOperationName,
        List<OperationProgress> operations
) {
}
