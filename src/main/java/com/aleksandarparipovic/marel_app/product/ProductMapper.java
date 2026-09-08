package com.aleksandarparipovic.marel_app.product;

import com.aleksandarparipovic.marel_app.product.dto.ProductBaseRow;
import com.aleksandarparipovic.marel_app.product.dto.ProductOptionDto;
import com.aleksandarparipovic.marel_app.product_type.ProductType;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public ProductOptionDto toDtoOption(Product p){
        return new ProductOptionDto(p.getId(), p.getProductName());
    }

    public ProductBaseRow toBaseRow(Product p) {
        ProductType type = p.getProductType();
        Long typeId = type != null ? type.getId() : null;
        String typeName = type != null ? type.getName() : null;
        Long familyId = type != null && type.getFamily() != null ? type.getFamily().getId() : null;
        String familyName = type != null && type.getFamily() != null ? type.getFamily().getName() : null;

        return new ProductBaseRow(
                p.getId(),
                p.getProductName(),
                p.getProductCode(),
                p.getDescription(),
                p.isActive(),
                typeId,
                typeName,
                familyId,
                familyName,
                p.getCatalogNumber(),
                p.getSubtype(),
                p.getSupervisorName(),
                p.getDisplayName(),
                effectiveDisplayName(p)
        );
    }

    /**
     * What the UI shows for a product: the stored override if there is one, else
     * the product name with the subtype appended when there is one — never a
     * trailing space when there is not.
     */
    private String effectiveDisplayName(Product p) {
        if (p.getDisplayName() != null && !p.getDisplayName().isBlank()) {
            return p.getDisplayName();
        }
        String name = p.getProductName();
        String subtype = p.getSubtype();
        return (subtype != null && !subtype.isBlank()) ? name + " " + subtype : name;
    }
}
