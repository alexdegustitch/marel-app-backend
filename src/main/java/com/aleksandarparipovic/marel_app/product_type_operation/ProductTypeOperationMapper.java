package com.aleksandarparipovic.marel_app.product_type_operation;

import com.aleksandarparipovic.marel_app.product_type_operation.dto.ProductTypeOperationDto;
import org.springframework.stereotype.Component;

@Component
public class ProductTypeOperationMapper {

    public ProductTypeOperationDto toDto(ProductTypeOperation o) {
        if (o == null) return null;

        return ProductTypeOperationDto.builder()
                .id(o.getId())
                .productTypeId(o.getProductType() != null ? o.getProductType().getId() : null)
                .opName(o.getOpName())
                .description(o.getDescription())
                .defaultMinNorm(o.getDefaultMinNorm())
                .defaultMaxNorm(o.getDefaultMaxNorm())
                .defaultUnitsPerProduct(o.getDefaultUnitsPerProduct())
                .workCodeCategoryId(o.getWorkCodeCategory() != null ? o.getWorkCodeCategory().getId() : null)
                .normRequired(o.getNormRequired())
                .sortOrder(o.getSortOrder())
                .active(o.getIsActive())
                .build();
    }
}
