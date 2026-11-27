package com.aleksandarparipovic.marel_app.production_order.dto;

import java.util.List;

public record ProductionOrderLineItemDto(
        Long id,
        Long productId,
        String productName,
        /** The ordered variant (V78); both null on a product without variants. */
        Long productVariantId,
        String productVariantLabel,
        String productDescription,
        Integer lineOrder,
        String note,
        List<ProductionOrderLineItemQuantityDto> quantities,
        List<ProductionOrderLineItemNoteDto> notes
) {
}
