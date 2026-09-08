package com.aleksandarparipovic.marel_app.operation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * "Give this product these operations from its type's template" — the create-a-
 * product / product-page flow. The ids name TEMPLATE operations
 * (product_type_operations); each is copied onto the target as a new, independent
 * operation. A name the target already carries is skipped rather than refused, so
 * "select all" is safe to press twice.
 */
@Getter
@Setter
public class CopyTypeOperationsRequest {

    @NotNull(message = "Proizvod je obavezan.")
    private Long targetProductId;

    @NotEmpty(message = "Izaberite bar jednu operaciju.")
    private List<Long> productTypeOperationIds;
}
