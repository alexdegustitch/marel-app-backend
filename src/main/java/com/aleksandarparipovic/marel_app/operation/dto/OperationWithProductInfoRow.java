package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class OperationWithProductInfoRow {

    private final Long operationId;
    private final Long productId;
    private final String operationName;
    private final String productName;
    private final Integer minNorm;
    private final Integer maxNorm;
   //  private final boolean normRequired;
    private final Integer unitsPerProduct;
    private final LocalDate normDate;
    private final Long workCodeCategoryId;
    private final Long operationCount;
    // The product's catalogue number — shown on the product band and searchable
    // alongside the product and operation names.
    private final String catalogNumber;
    // The norm in force was entered without a date on purpose ("privremena").
    // When true, normDate is null and the grid reads "Privremena" in its place.
    private final boolean normTemporary;


}
