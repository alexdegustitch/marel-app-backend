package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The operations board's KPI figures, answered in one request. Counts cover
 * live (non-archived) operations only — the same population the search grid
 * pages through, so the tiles and the grid can never disagree about the total.
 */
@Getter
@AllArgsConstructor
public class OperationStatsRow {
    private long total;
    /** Operations with no norm in force ({@code minNorm} is null). */
    private long withoutNorm;
    /** Operations not assigned a work code category. */
    private long withoutCategory;
    /** Distinct products those live operations belong to. */
    private long products;
}
