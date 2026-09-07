package com.aleksandarparipovic.marel_app.product.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The product board's four figures, answered in one request. Counts cover
 * live (non-archived) products only; {@code totalOperations} counts live
 * operations on live products, so the two figures can never disagree about
 * what "the catalogue" is.
 */
@Getter
@AllArgsConstructor
public class ProductStatsRow {
    private long total;
    private long active;
    private long inactive;
    private long withoutOperations;
    private long totalOperations;
}
