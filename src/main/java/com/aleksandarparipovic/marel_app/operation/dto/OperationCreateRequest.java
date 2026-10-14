package com.aleksandarparipovic.marel_app.operation.dto;


import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class OperationCreateRequest {

    /** Required for a product operation; must be absent for a standalone one. */
    private Long productId;

    /**
     * TRUE creates an OSNOVNA (standalone) operation: no product, no norm
     * fields, category required. The service refuses mixtures out loud.
     */
    private Boolean standalone;

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
