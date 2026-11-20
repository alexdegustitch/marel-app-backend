package com.aleksandarparipovic.marel_app.operation.dto;

/** Null clears the category — allowed everywhere except on a standalone operation. */
public record OperationCategoryChangeRequest(Long workCodeCategoryId) {
}
