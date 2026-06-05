package com.aleksandarparipovic.marel_app.product_manufacturing_time.dto;

import com.aleksandarparipovic.marel_app.product_manufacturing_time.ProductManufacturingTime;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
public class ProductManufacturingTimeDto {

    private final Long id;
    private final Long userId;
    private final Long operationId;
    private final String operationName;
    private final LocalDate manufacturingDate;

    private final Integer unitsPerProductSnapshot;
    private final Boolean unitsPerProductOverridden;
    private final Integer unitsPerProductValue;

    private final BigDecimal normSnapshot;
    private final Boolean normOverridden;
    private final BigDecimal normValue;

    private final LocalDate normDateSnapshot;
    private final Boolean normDateOverridden;
    private final LocalDate normDateValue;

    private final Boolean excluded;
    private final BigDecimal manufacturingCoefficient;
    private final BigDecimal productsPerHour;

    // Manufacturing time as total seconds; format as mm:ss on the client
    private final Integer manufacturingTimeSeconds;

    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
    private final OffsetDateTime archivedAt;
    private final Boolean active;

    public ProductManufacturingTimeDto(ProductManufacturingTime e) {
        this.id = e.getId();
        this.userId = e.getUser().getId();
        this.operationId = e.getOperation().getId();
        this.operationName = e.getOperationName();
        this.manufacturingDate = e.getManufacturingDate();
        this.unitsPerProductSnapshot = e.getUnitsPerProductSnapshot();
        this.unitsPerProductOverridden = e.getUnitsPerProductOverridden();
        this.unitsPerProductValue = e.getUnitsPerProductValue();
        this.normSnapshot = e.getNormSnapshot();
        this.normOverridden = e.getNormOverridden();
        this.normValue = e.getNormValue();
        this.normDateSnapshot = e.getNormDateSnapshot();
        this.normDateOverridden = e.getNormDateOverridden();
        this.normDateValue = e.getNormDateValue();
        this.excluded = e.getExcluded();
        this.manufacturingCoefficient = e.getManufacturingCoefficient();
        this.productsPerHour = e.getProductsPerHour();
        this.manufacturingTimeSeconds = e.getManufacturingTimeSeconds();
        this.createdAt = e.getCreatedAt();
        this.updatedAt = e.getUpdatedAt();
        this.archivedAt = e.getArchivedAt();
        this.active = e.getActive();
    }
}

