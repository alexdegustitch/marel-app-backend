package com.aleksandarparipovic.marel_app.operation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * "Give this product these operations" — the product page's add-from-catalogue
 * flow. The ids name EXISTING operations (on any product); each is copied onto
 * the target as a new operation. A name the target already carries is skipped
 * rather than refused, so "select all" is safe to press twice.
 */
@Getter
@Setter
public class CopyOperationsRequest {

    @NotNull(message = "Proizvod je obavezan.")
    private Long targetProductId;

    @NotEmpty(message = "Izaberite bar jednu operaciju.")
    private List<Long> operationIds;
}
