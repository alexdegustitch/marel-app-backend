package com.aleksandarparipovic.marel_app.product_manufacturing_time.dto;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ProductManufacturingTimeUpdateRequest {

    private String title;

    /** One note about the calculation as a whole. */
    private String note;
    private BigDecimal manufacturingCoefficient;
    private BigDecimal productsPerHour;
    private Integer manufacturingTimeSeconds;

    // When provided, replaces all existing operations for this ProductManufacturingTime
    @Valid
    private List<ProductManufacturingTimeOperationRequest> operations;
}
