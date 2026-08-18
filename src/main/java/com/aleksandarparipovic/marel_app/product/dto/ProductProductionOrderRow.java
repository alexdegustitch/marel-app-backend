package com.aleksandarparipovic.marel_app.product.dto;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrderStatus;

import java.time.LocalDate;

/**
 * One production order the product appears on, as the product page needs it:
 * enough to recognise the order and to open it, plus this product's own
 * quantity on that order. It is NOT the order's full card — the order page
 * remains the place for that.
 */
public record ProductProductionOrderRow(
        Long orderId,
        String code,
        String name,
        ProductionOrderStatus status,
        LocalDate orderDate,
        String deliveryDeadline,
        Integer quantity,
        String lineNote
) {
}
