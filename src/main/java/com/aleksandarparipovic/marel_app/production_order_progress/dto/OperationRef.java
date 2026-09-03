package com.aleksandarparipovic.marel_app.production_order_progress.dto;

/** Enough to name an operation and the product it belongs to. */
public record OperationRef(Long operationId, String operationName, Long productId, String productName) {
}
