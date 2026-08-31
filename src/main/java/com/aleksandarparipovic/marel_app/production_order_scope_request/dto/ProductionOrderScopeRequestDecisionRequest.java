package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** What a processor or a requester sends when declining or withdrawing. */
@Getter
@Setter
public class ProductionOrderScopeRequestDecisionRequest {

    @Size(max = 2000, message = "Napomena može imati najviše 2000 karaktera")
    private String decisionNote;
}
