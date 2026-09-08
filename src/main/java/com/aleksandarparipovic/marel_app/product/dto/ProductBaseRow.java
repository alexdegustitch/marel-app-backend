package com.aleksandarparipovic.marel_app.product.dto;

public record ProductBaseRow(
        Long productId,
        String productName,
        String productCode,
        String description,
        Boolean active,

        // Catalogue hierarchy + catalogue fields (all optional; null = uncategorised).
        Long productTypeId,
        String productTypeName,
        Long familyId,
        String familyName,
        String catalogNumber,
        String subtype,
        String supervisorName,
        /** The stored override, or null when the name is derived. */
        String displayName,
        /** What to actually show: displayName, else productName [+ " " + subtype]. */
        String effectiveDisplayName
) {}
