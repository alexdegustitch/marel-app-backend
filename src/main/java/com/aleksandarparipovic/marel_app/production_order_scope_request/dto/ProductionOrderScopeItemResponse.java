package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

import java.util.List;

/** One covered line with the operations decided — or proposed — on it. */
public record ProductionOrderScopeItemResponse(
        ProductionOrderScopeLineResponse line,
        List<ProductionOrderScopeOperationResponse> operations
) {
}
