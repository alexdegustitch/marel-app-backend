package com.aleksandarparipovic.marel_app.product_type_operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A new template operation for a type. The type is taken from the path, not the
 * body. Only the name is required; the norm defaults are optional suggestions.
 */
@Getter
@Setter
public class ProductTypeOperationCreateRequest {

    @NotBlank(message = "Naziv operacije je obavezan.")
    @Size(max = 255, message = "Naziv operacije je predugačak.")
    private String opName;

    private String description;

    private Integer defaultMinNorm;

    private Integer defaultMaxNorm;

    private Integer defaultUnitsPerProduct;

    private Long workCodeCategoryId;

    /** Whether a copied operation requires a norm. Defaults to true when absent. */
    private Boolean normRequired;

    private Integer sortOrder;
}
