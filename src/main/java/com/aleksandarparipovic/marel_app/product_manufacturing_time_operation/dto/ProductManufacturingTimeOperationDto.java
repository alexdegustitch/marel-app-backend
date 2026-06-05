package com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.dto;

import com.aleksandarparipovic.marel_app.product_manufacturing_time_operation.ProductManufacturingTimeOperation;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
public class ProductManufacturingTimeOperationDto {

    private final Long id;
    private final Long productManufacturingTimeId;
    private final Long operationId;
    private final String operationName;

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

    public ProductManufacturingTimeOperationDto(ProductManufacturingTimeOperation e) {
        this.id = e.getId();
        this.productManufacturingTimeId = e.getProductManufacturingTime().getId();
        this.operationId = e.getOperation().getId();
        this.operationName = e.getOperationName();
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
    }
}

