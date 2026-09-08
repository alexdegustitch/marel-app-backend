package com.aleksandarparipovic.marel_app.product_type;

import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeDto;
import com.aleksandarparipovic.marel_app.product_type.dto.ProductTypeOptionDto;
import org.springframework.stereotype.Component;

@Component
public class ProductTypeMapper {

    public ProductTypeDto toDto(ProductType t) {
        if (t == null) return null;

        return ProductTypeDto.builder()
                .id(t.getId())
                .familyId(t.getFamily() != null ? t.getFamily().getId() : null)
                .familyName(t.getFamily() != null ? t.getFamily().getName() : null)
                .name(t.getName())
                .code(t.getCode())
                .description(t.getDescription())
                .note(t.getNote())
                .standard(t.getStandard())
                .sortOrder(t.getSortOrder())
                .active(t.getIsActive())
                .archivedAt(t.getArchivedAt())
                .build();
    }

    public ProductTypeOptionDto toOptionDto(ProductType t) {
        if (t == null) return null;
        return new ProductTypeOptionDto(
                t.getId(),
                t.getFamily() != null ? t.getFamily().getId() : null,
                t.getName(),
                t.getCode());
    }
}
