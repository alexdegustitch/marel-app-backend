package com.aleksandarparipovic.marel_app.product_type_attribute.dto;

import com.aleksandarparipovic.marel_app.product_type_attribute.AttributeDataType;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Null means "leave it". The attribute stays with its type — moving an attribute
 * between types is not a thing (its recorded values belong to this type's products).
 */
@Getter
@Setter
public class ProductTypeAttributeUpdateRequest {

    @Size(max = 100, message = "Naziv atributa je predugačak.")
    private String name;

    @Size(max = 30, message = "Jedinica je predugačka.")
    private String unit;

    private AttributeDataType dataType;

    private Integer sortOrder;

    private Boolean required;

    private Boolean active;
}
