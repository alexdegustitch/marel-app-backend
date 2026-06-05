package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class ProductManufacturingTimeOperationUpdateRequest {

    private Integer unitsPerProductSnapshot;
    private Boolean unitsPerProductOverridden;
    private Integer unitsPerProductValue;

    private BigDecimal normSnapshot;
    private Boolean normOverridden;
    private BigDecimal normValue;

    private LocalDate normDateSnapshot;
    private Boolean normDateOverridden;
    private LocalDate normDateValue;

    private Boolean excluded;
}

