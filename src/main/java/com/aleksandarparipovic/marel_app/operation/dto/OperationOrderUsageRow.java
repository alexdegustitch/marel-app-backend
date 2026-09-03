package com.aleksandarparipovic.marel_app.operation.dto;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;

import java.time.LocalDate;

/**
 * A production order this operation is worked for, with its progress.
 *
 * <p>{@code requiredPieces} is the order's quantity of the product multiplied by
 * how many pieces of THIS operation one product needs; {@code donePieces} is what
 * the work logs recorded against this operation on this order. Both are null-safe:
 * an operation with no quantity-per-product cannot state a requirement, and says
 * so with null rather than pretending the requirement is zero.
 *
 * <p>The multiplier is taken from the ORDER'S agreed scope when that order has
 * one, and from the catalogue otherwise. The two can differ on purpose: a scope
 * exists precisely to say that this order's variant needs three of something the
 * catalogue lists as one, or none at all. {@code requirementFromScope} says which
 * of the two answered, so a screen can show a figure the floor agreed to rather
 * than one it would dispute.
 */
public record OperationOrderUsageRow(
        Long orderId,
        String code,
        String name,
        ProductionOrderStatus status,
        LocalDate orderDate,
        String deliveryDeadline,
        Integer orderedQuantity,
        Integer requiredPieces,
        Integer donePieces,
        /** True when requiredPieces came from the order's razrada, not the catalogue. */
        boolean requirementFromScope
) {
}
