package com.aleksandarparipovic.marel_app.operation.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class OperationDto {

    private Long id;
    private Long productId;
    private String operationName;
    private Integer minNorm;
    private Integer maxNorm;
    private Integer unitsPerProduct;
    private LocalDate normDate;

}
