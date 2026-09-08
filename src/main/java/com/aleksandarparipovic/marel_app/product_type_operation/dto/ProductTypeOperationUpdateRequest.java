package com.aleksandarparipovic.marel_app.product_type_operation.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Null means "leave it". The template operation stays with its type — moving one
 * between types is not a thing. Mirrors the product-type-attribute update: this is
 * a partial edit, so a field absent from the body is not touched.
 */
@Getter
@Setter
public class ProductTypeOperationUpdateRequest {

    @Size(max = 255, message = "Naziv operacije je predugačak.")
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
