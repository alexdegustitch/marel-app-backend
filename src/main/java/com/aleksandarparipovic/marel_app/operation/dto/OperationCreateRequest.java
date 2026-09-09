package com.aleksandarparipovic.marel_app.operation.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class OperationCreateRequest {

    @NotNull
    private Long productId;

    @NotBlank
    private String operationName;

    private LocalDate normDate;

    private Integer minNorm;

    private Integer maxNorm;

    private Boolean normRequired;

    // The norm is entered without a date on purpose ("privremena"). Only
    // meaningful when a norm value is present; excludes normDate.
    private Boolean temporary;

    private Integer unitsPerProduct;

    private Long workCodeCategoryId;
}
