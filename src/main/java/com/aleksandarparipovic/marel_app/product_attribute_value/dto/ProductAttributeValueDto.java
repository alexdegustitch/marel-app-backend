package com.aleksandarparipovic.marel_app.product_attribute_value.dto;

import com.aleksandarparipovic.marel_app.product_type_attribute.AttributeDataType;
import lombok.Builder;
import lombok.Getter;

/**
 * One row of a product's spec form: the attribute definition joined with the
 * product's current value for it. {@code value} is null when the product has not
 * filled this attribute in.
 */
@Getter
@Builder
public class ProductAttributeValueDto {

    private Long attributeId;
    private String name;
    private String unit;
    private AttributeDataType dataType;
    private Integer sortOrder;
    private Boolean required;
    private String value;
}
