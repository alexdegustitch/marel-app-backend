package com.aleksandarparipovic.marel_app.product_manufacturing_time.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ProductManufacturingTimeCreateRequest {

    @NotNull
    private Long productId;

    @NotBlank
    private String title;

    @NotBlank
    private String productName;

    private BigDecimal manufacturingCoefficient;
    private BigDecimal productsPerHour;
    private Integer manufacturingTimeSeconds;

    @Valid
    private List<ProductManufacturingTimeOperationRequest> operations = new ArrayList<>();
}
