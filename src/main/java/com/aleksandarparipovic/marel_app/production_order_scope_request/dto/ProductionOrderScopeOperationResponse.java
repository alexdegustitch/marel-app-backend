package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

/**
 * One operation of a covered line's product, as the completion modal shows it.
 *
 * <p>Before anything is saved these come from the product's catalogue with
 * {@code needed} true and the catalogue quantity in both quantity fields — the
 * proposal the processor edits. After a save they are the decided rows, and
 * {@code unitsPerProductSnapshot} is what the catalogue said at the time, so an
 * override is visible as an override.
 */
public record ProductionOrderScopeOperationResponse(
        Long operationId,
        String operationName,
        boolean needed,
        Integer unitsPerProductSnapshot,
        Integer unitsPerProduct
) {
}
