package com.aleksandarparipovic.marel_app.product_type_attribute.dto;

import com.aleksandarparipovic.marel_app.product_type_attribute.AttributeDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A new spec column for a type. The type is taken from the path, not the body.
 * Only the name is required; data type defaults to TEXT.
 */
@Getter
@Setter
public class ProductTypeAttributeCreateRequest {

    @NotBlank(message = "Naziv atributa je obavezan.")
    @Size(max = 100, message = "Naziv atributa je predugačak.")
    private String name;

    @Size(max = 30, message = "Jedinica je predugačka.")
    private String unit;

    /** NUMBER or TEXT; defaults to TEXT when absent. */
    private AttributeDataType dataType;

    private Integer sortOrder;

    private Boolean required;
}
