package com.aleksandarparipovic.marel_app.sample_order.dto;

import java.util.List;

public record SampleOrderLineItemDto(
        Long id,
        Long productId,
        String productName,
        String productCode,
        /** The opis the shop floor works from. */
        String productDescription,
        Integer lineOrder,
        /** The live number. The revisions behind it are in {@link #quantities}. */
        Integer quantity,
        String note,
        List<SampleOrderLineItemQuantityDto> quantities,
        List<SampleOrderLineItemNoteDto> notes
) {
}
