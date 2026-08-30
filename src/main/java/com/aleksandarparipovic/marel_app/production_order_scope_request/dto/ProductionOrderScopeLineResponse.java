package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

/**
 * One covered order line, as a queue row shows it: which product, how many, and
 * what the requester wrote about it.
 */
public record ProductionOrderScopeLineResponse(
        /** The request's own item id — what the result payload addresses. */
        Long itemId,
        Long productionOrderLineItemId,
        Long productId,
        String productName,
        /** The line's own description, which may name a variant the product does not. */
        String productDescription,
        Integer quantity,
        String note
) {
}
