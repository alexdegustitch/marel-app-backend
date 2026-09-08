package com.aleksandarparipovic.marel_app.product_type_attribute.dto;

import com.aleksandarparipovic.marel_app.product_type_attribute.AttributeDataType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductTypeAttributeDto {

    private Long id;
    private Long productTypeId;
    private String name;
    private String unit;
    private AttributeDataType dataType;
    private Integer sortOrder;
    private Boolean required;
    private Boolean active;
}
