package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Optional body for assignment. When {@code assigneeUserId} is omitted the caller
 * claims the request for themselves, which is the common case.
 */
@Getter
@Setter
public class ProductionOrderScopeRequestAssignRequest {
    private Long assigneeUserId;
}
