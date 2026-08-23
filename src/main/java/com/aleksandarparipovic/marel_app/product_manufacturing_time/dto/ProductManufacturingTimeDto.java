package com.aleksandarparipovic.marel_app.product_manufacturing_time.dto;

import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationDto;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
public class ProductManufacturingTimeDto {

    private final Long id;
    private final Long userId;
    private final String title;

    /** One note about the calculation as a whole. */
    private final String note;
    private final Long productId;
    private final String productName;
    private final LocalDate dateOfIssue;

    private final BigDecimal manufacturingCoefficient;
    private final BigDecimal productsPerHour;

    // Manufacturing time as total seconds; format as mm:ss on the client
    private final Integer manufacturingTimeSeconds;

    private final List<ProductManufacturingTimeOperationDto> operations;

    public ProductManufacturingTimeDto(ProductManufacturingTime e, List<ProductManufacturingTimeOperationDto> operations) {
        this.id = e.getId();
        this.title = e.getTitle();
        this.note = e.getNote();
        this.userId = e.getUser().getId();
        this.productId = e.getProduct().getId();
        this.productName = e.getProductName();
        this.dateOfIssue = e.getDateOfIssue();
        this.manufacturingCoefficient = e.getManufacturingCoefficient();
        this.productsPerHour = e.getProductsPerHour();
        this.manufacturingTimeSeconds = e.getManufacturingTimeSeconds();
        this.operations = operations;
    }
}
