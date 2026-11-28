package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequestScope;
import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequestStatus;
import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeResultState;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One row of the requests screen.
 *
 * <p>Carries the covered lines but NOT the operations decided on them: a queue
 * shows what was asked and how far it got, and the answer itself is read by
 * opening the request.
 */
public record ProductionOrderScopeRequestResponse(
        Long id,
        Long productionOrderId,
        String productionOrderCode,
        String productionOrderName,
        ProductionOrderScopeRequestScope scope,
        ProductionOrderScopeRequestStatus status,
        /** NULL until the processor saves anything. */
        ProductionOrderScopeResultState resultState,
        Long createdByUserId,
        String createdByName,
        Long assignedToUserId,
        String assignedToName,
        Long processedByUserId,
        String processedByName,
        OffsetDateTime processedAt,
        String decisionNote,
        OffsetDateTime cancelledAt,
        OffsetDateTime createdAt,
        /**
         * Raised and answered by the same person, straight from the order — so the
         * "whoever asks does not decide" rule does not apply to it. The screens need
         * to tell the two apart: on an ordinary request of their own the caller can
         * only wait, on a self-request they carry on.
         */
        boolean internal,
        /** The order lines this request covers, in the order the order lists them. */
        List<ProductionOrderScopeLineResponse> lines
) {
}
