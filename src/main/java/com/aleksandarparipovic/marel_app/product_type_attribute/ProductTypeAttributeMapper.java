package com.aleksandarparipovic.marel_app.product_type_attribute;

import com.aleksandarparipovic.marel_app.product_type_attribute.dto.ProductTypeAttributeDto;
import org.springframework.stereotype.Component;

@Component
public class ProductTypeAttributeMapper {

    public ProductTypeAttributeDto toDto(ProductTypeAttribute a) {
        if (a == null) return null;

        return ProductTypeAttributeDto.builder()
                .id(a.getId())
                .productTypeId(a.getProductType() != null ? a.getProductType().getId() : null)
                .name(a.getName())
                .unit(a.getUnit())
                .dataType(a.getDataType())
                .sortOrder(a.getSortOrder())
                .required(a.getRequired())
                .active(a.getIsActive())
                .build();
    }
}
