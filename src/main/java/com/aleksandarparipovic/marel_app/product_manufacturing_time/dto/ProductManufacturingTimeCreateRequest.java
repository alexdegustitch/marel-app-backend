package com.aleksandarparipovic.marel_app.product_manufacturing_time.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class ProductManufacturingTimeCreateRequest {

    @NotNull
    private Long operationId;

    @NotNull
    private LocalDate manufacturingDate;

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

    private BigDecimal manufacturingCoefficient;
    private BigDecimal productsPerHour;

    // Manufacturing time in total seconds (mm:ss represented as seconds)
    private Integer manufacturingTimeSeconds;
}

