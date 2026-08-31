package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

import com.aleksandarparipovic.marel_app.production_order_scope_request.ProductionOrderScopeRequestScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Everything a requester may supply. Status, creator, timestamps and assignment
 * are all derived server-side.
 */
@Getter
@Setter
public class ProductionOrderScopeRequestCreateRequest {

    @NotNull(message = "Proizvodni nalog je obavezan")
    private Long productionOrderId;

    @NotNull(message = "Obim zahteva je obavezan")
    private ProductionOrderScopeRequestScope scope;

    /**
     * The lines to cover, with the note the requester wrote about each.
     *
     * <p>Optional for an ORDER-wide request: left out, the server covers every
     * active line of the order and prefills each note from the line's own. The
     * client normally sends them anyway, because the requester is offered those
     * defaults to edit before submitting.
     *
     * <p>Required, and exactly one, for a LINE_ITEM request.
     */
    @Valid
    private List<Item> items;

    @Getter
    @Setter
    public static class Item {

        @NotNull(message = "Stavka naloga je obavezna")
        private Long productionOrderLineItemId;

        /** Blank means "nothing to say about this line", not an error. */
        @Size(max = 2000, message = "Napomena može imati najviše 2000 karaktera")
        private String note;
    }
}
