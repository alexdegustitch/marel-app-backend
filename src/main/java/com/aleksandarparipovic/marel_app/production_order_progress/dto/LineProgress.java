package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/**
 * How many whole pieces ONE line of the order may have finished — as a RANGE,
 * because a work log names an operation, not a line: when an operation is
 * shared between lines (product-level, or bound to a value both variants
 * carry), nobody can say whose pieces they were (SPEC 5.6, mera 2).
 *
 * <p>Both bounds are achievable for THIS line alone; the bounds of different
 * lines are NOT simultaneously achievable, which is why the product's own
 * total range is computed jointly and is usually narrower than these suggest.
 *
 * @param minFinished pieces this line finished even in the least favourable
 *                    reading — every shared pool first fills the other lines
 * @param maxFinished pieces it finished in the most favourable one — every
 *                    shared pool is read as its own
 */
public record LineProgress(
        Long lineItemId,
        Long productVariantId,
        String variantLabel,
        long quantity,
        long minFinished,
        long maxFinished
) {
}
