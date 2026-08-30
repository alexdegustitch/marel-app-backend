package com.aleksandarparipovic.marel_app.production_order_scope_request.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * The scope the processor decided: for every covered line, every operation of
 * that line's product with a yes/no and a quantity per assembly.
 *
 * <p>The SAME payload saves a draft and submits the answer — the two differ only
 * in which endpoint it is sent to, so a processor who saved and then submitted
 * cannot end up handing over something other than what they were looking at.
 */
@Getter
@Setter
public class ProductionOrderScopeResultRequest {

    /** Carried on submission; ignored when only saving. */
    @Size(max = 2000, message = "Napomena može imati najviše 2000 karaktera")
    private String decisionNote;

    @NotEmpty(message = "Razrada mora da sadrži bar jednu stavku")
    @Valid
    private List<Item> items;

    @Getter
    @Setter
    public static class Item {

        /**
         * The request's own item id, not the order line's. The set of covered
         * lines is fixed when the request is raised — a processor decides what is
         * needed, never which lines the request is about.
         */
        @NotNull(message = "Stavka zahteva je obavezna")
        private Long itemId;

        @NotEmpty(message = "Stavka mora da sadrži bar jednu operaciju")
        @Valid
        private List<Operation> operations;
    }

    @Getter
    @Setter
    public static class Operation {

        @NotNull(message = "Operacija je obavezna")
        private Long operationId;

        /** Defaults to needed: leaving the flag out must not silently drop work. */
        private boolean needed = true;

        /**
         * How many go into one assembly. Required when the operation is needed,
         * which the service checks — a message a person can act on, rather than
         * the database's constraint violation.
         */
        @Positive(message = "Količina u sklopu mora da bude veća od nule")
        private Integer unitsPerProduct;
    }
}
