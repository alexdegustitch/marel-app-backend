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
    private final Integer unitsPerProduct;
    private final LocalDate normDate;
    private final Long operationCount;

}
