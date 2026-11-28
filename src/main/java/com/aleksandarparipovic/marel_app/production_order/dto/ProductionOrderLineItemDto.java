package com.aleksandarparipovic.marel_app.production_order.dto;

import java.util.List;

public record ProductionOrderLineItemDto(
        Long id,
        Long productId,
        String productName,
        /** The ordered variant (V78); both null on a product without variants. */
        Long productVariantId,
        String productVariantLabel,
        /**
         * The same variant spelled out attribute by attribute
         * ("Šraf: Al · Navrtka: Prohrom"), for screens that print it after the
         * product name. Null on a product without variants, and on a variant
         * that carries no values.
         */
        String productVariantAttributes,
        String productDescription,
        Integer lineOrder,
        String note,
        List<ProductionOrderLineItemQuantityDto> quantities,
        List<ProductionOrderLineItemNoteDto> notes
) {
}
