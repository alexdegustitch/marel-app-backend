package com.aleksandarparipovic.marel_app.product_variant.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** The wire shape of product variants: one DTO, one value row, two requests. */
public final class ProductVariantDtos {

    private ProductVariantDtos() {
    }

    @Getter
    @Builder
    public static class ProductVariantDto {
        private Long id;
        private Long productId;
        private String label;
        /** The option values joined for display: "Prohrom / Al". */
        private String valuesLabel;
        private List<ProductVariantValueDto> values;
        private Boolean active;
        private boolean archived;
        /** Referenced by an order line or a manufacturing time — values are frozen. */
        private boolean inUse;
    }

    /**
     * One variant that would be left without a value if an attribute became
     * required — the list shown before the "obavezan" flip, so whoever flips it
     * knows exactly what to fill in (and which variants are frozen and cannot be).
     */
    @Getter
    @Builder
    public static class VariantMissingValueRow {
        private Long variantId;
        private Long productId;
        private String productName;
        /** Ručni naziv ako postoji, inače spojene vrednosti ("Prohrom / Al"). */
        private String variantLabel;
        /** Frozen (referenced): values cannot be added — a change is a NEW variant. */
        private boolean inUse;
    }

    @Getter
    @Builder
    public static class ProductVariantValueDto {
        private Long attributeId;
        private String attributeName;
        private Long optionId;
        private String optionValue;
    }

    @Getter
    @Setter
    public static class ProductVariantCreateRequest {
        @Size(max = 255, message = "Naziv varijante je predugačak.")
        private String label;
        /** One option per attribute; every required attribute of the type must be covered. */
        @NotEmpty(message = "Varijanta mora imati bar jednu vrednost atributa.")
        private List<Long> optionIds;
    }

    /**
     * Null means "leave it". {@code optionIds} is refused once the variant is
     * referenced anywhere — a different combination is a NEW variant.
     */
    @Getter
    @Setter
    public static class ProductVariantUpdateRequest {
        @Size(max = 255, message = "Naziv varijante je predugačak.")
        private String label;
        private List<Long> optionIds;
    }
}
