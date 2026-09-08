package com.aleksandarparipovic.marel_app.product_family;

import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyDto;
import com.aleksandarparipovic.marel_app.product_family.dto.ProductFamilyOptionDto;
import org.springframework.stereotype.Component;

@Component
public class ProductFamilyMapper {

    public ProductFamilyDto toDto(ProductFamily f) {
        if (f == null) return null;

        return ProductFamilyDto.builder()
                .id(f.getId())
                .name(f.getName())
                .description(f.getDescription())
                .sortOrder(f.getSortOrder())
                .active(f.getIsActive())
                .archivedAt(f.getArchivedAt())
                .build();
    }

    public ProductFamilyOptionDto toOptionDto(ProductFamily f) {
        if (f == null) return null;
        return new ProductFamilyOptionDto(f.getId(), f.getName());
    }
}
