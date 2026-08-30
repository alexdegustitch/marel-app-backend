package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

import java.util.List;

/**
 * One request with its answer, which is what the completion modal reads.
 *
 * <p>{@code editable} is the server's answer to "may THIS caller change this
 * now", so the modal never has to reconstruct the rule from status, result state
 * and assignment — three facts that can only be combined one correct way.
 */
public record ProductionOrderScopeRequestDetailResponse(
        ProductionOrderScopeRequestResponse request,
        List<ProductionOrderScopeItemResponse> items,
        boolean editable
) {
}
