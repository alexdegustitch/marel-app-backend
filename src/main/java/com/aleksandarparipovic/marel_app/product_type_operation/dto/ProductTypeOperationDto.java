package com.aleksandarparipovic.marel_app.product_type_operation.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * A template operation as the admin screen and the "copy from type" picker see it.
 * The norm fields are the SUGGESTED defaults a copy starts from, not a live norm.
 */
@Getter
@Builder
public class ProductTypeOperationDto {

    private Long id;
    private Long productTypeId;
    private String opName;
    private String description;
    private Integer defaultMinNorm;
    private Integer defaultMaxNorm;
    private Integer defaultUnitsPerProduct;
    private Long workCodeCategoryId;
    private Boolean normRequired;
    private Integer sortOrder;
    private Boolean active;
}
