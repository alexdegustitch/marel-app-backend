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
        Integer donePieces
) {
}
