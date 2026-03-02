package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@AllArgsConstructor
@Data
public class OperationWithProductNameDto {
    private Long id;
    private String operationName;
    private Integer minNorm;
    private Integer maxNorm;
    private LocalDate normDate;
    private Integer unitsPerProduct;
    private Long productId;
    private String productName;
}
