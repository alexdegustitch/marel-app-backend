package com.aleksandarparipovic.marel_app.variant_attribute.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * The whole wire shape of the variant-attribute šifarnik in one file: four
 * small request classes and two DTOs. Kept together because they move
 * together — a field added to one is a field added to all.
 */
public final class VariantAttributeDtos {

    private VariantAttributeDtos() {
    }

    @Getter
    @Builder
    public static class VariantAttributeDto {
        private Long id;
        private Long productTypeId;
        private String name;
        private Boolean required;
        private Integer sortOrder;
        private Boolean active;
        private boolean archived;
        private List<VariantAttributeOptionDto> options;
    }

    @Getter
    @Builder
    public static class VariantAttributeOptionDto {
        private Long id;
        private Long variantAttributeId;
        private String value;
        private Integer sortOrder;
        private Boolean active;
        private boolean archived;
    }

    @Getter
    @Setter
    public static class VariantAttributeCreateRequest {
        @NotBlank(message = "Naziv varijantnog atributa je obavezan.")
        @Size(max = 100, message = "Naziv varijantnog atributa je predugačak.")
        private String name;
        /** Defaults to true: an attribute usually must be picked in every variant. */
        private Boolean required;
        private Integer sortOrder;
    }

    /** Null means "leave it". The attribute stays with its type. */
    @Getter
    @Setter
    public static class VariantAttributeUpdateRequest {
        @Size(max = 100, message = "Naziv varijantnog atributa je predugačak.")
        private String name;
        private Boolean required;
        private Integer sortOrder;
    }

    @Getter
    @Setter
    public static class VariantAttributeOptionCreateRequest {
        @NotBlank(message = "Vrednost opcije je obavezna.")
        @Size(max = 100, message = "Vrednost opcije je predugačka.")
        private String value;
        private Integer sortOrder;
    }

    @Getter
    @Setter
    public static class VariantAttributeOptionUpdateRequest {
        @Size(max = 100, message = "Vrednost opcije je predugačka.")
        private String value;
        private Integer sortOrder;
    }
}
