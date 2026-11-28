package com.aleksandarparipovic.marel_app.operation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * V77 — the whole execution order of one product's operations, written at
 * once: the product page's "Redosled izrade" drawer saves a drag-and-drop
 * rearrangement as ONE transaction, so no half-moved state is ever visible.
 *
 * <p>{@code sortOrder} null = unplaced. An operation left out keeps its step.
 */
public record OperationStepsRequest(
        @NotNull Long productId,
        @NotEmpty List<Step> steps
) {
    public record Step(@NotNull Long operationId, Integer sortOrder) {
    }
}
