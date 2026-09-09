package com.aleksandarparipovic.marel_app.search.dto;

/**
 * Minimal operation row for Sparky's fuzzy resolver — carries the owning
 * {@code productId} so operations can be scoped to a resolved product.
 */
public interface OperationMatchRow {
    Long getId();
    String getOpName();
    Long getProductId();
    String getProductName();
}
