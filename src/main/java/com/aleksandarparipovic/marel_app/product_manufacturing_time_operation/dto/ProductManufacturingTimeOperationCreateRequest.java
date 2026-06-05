package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class ProductManufacturingTimeOperationCreateRequest {

    @NotNull
    private Long productManufacturingTimeId;

    @NotNull
    private Long operationId;

    @NotBlank
    private String operationName;

    private Integer unitsPerProductSnapshot;
    private Boolean unitsPerProductOverridden = false;
    private Integer unitsPerProductValue;

    private BigDecimal normSnapshot;
    private Boolean normOverridden = false;
    private BigDecimal normValue;

    private LocalDate normDateSnapshot;
    private Boolean normDateOverridden = false;
    private LocalDate normDateValue;

    private Boolean excluded = false;
}

