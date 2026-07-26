package com.aleksandarparipovic.marel_app.production_order.dto;

import java.util.List;

public record ProductionOrderLineItemDto(
        Long id,
        Long productId,
        String productName,
        String productDescription,
        Integer lineOrder,
        String note,
        List<ProductionOrderLineItemQuantityDto> quantities,
        List<ProductionOrderLineItemNoteDto> notes
) {
}
