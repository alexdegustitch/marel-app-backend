package com.aleksandarparipovic.marel_app.product_manufacturing_time.dto;

import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto.ProductManufacturingTimeOperationDto;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    /**
     * The moment the record was written, clock time included. {@code dateOfIssue}
     * keeps the day; the screen shows this one when it wants the hour too.
     */
    private final OffsetDateTime createdAt;

    private final BigDecimal manufacturingCoefficient;
    private final BigDecimal productsPerHour;

    // Manufacturing time as total seconds; format as mm:ss on the client
    private final Integer manufacturingTimeSeconds;

    /**
     * Whether some request points at this record as its answer. Such a record is
     * shared evidence — the screen greys its delete out and says why.
     */
    private final boolean answersRequests;

    private final List<ProductManufacturingTimeOperationDto> operations;

    public ProductManufacturingTimeDto(ProductManufacturingTime e, List<ProductManufacturingTimeOperationDto> operations) {
        this(e, operations, false);
    }

    public ProductManufacturingTimeDto(
            ProductManufacturingTime e,
            List<ProductManufacturingTimeOperationDto> operations,
            boolean answersRequests
    ) {
        this.id = e.getId();
        this.title = e.getTitle();
        this.note = e.getNote();
        this.userId = e.getUser().getId();
        this.productId = e.getProduct().getId();
        this.productName = e.getProductName();
        this.dateOfIssue = e.getDateOfIssue();
        this.createdAt = e.getCreatedAt();
        this.manufacturingCoefficient = e.getManufacturingCoefficient();
        this.productsPerHour = e.getProductsPerHour();
        this.manufacturingTimeSeconds = e.getManufacturingTimeSeconds();
        this.answersRequests = answersRequests;
        this.operations = operations;
    }
}
