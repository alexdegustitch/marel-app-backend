package com.aleksandarparipovic.marel_app.production_order.dto;

import java.util.List;

/**
 * One line of a past order, as the copy picker needs it.
 *
 * <p><b>Carries everything a copy needs, not everything a line has.</b> The
 * product, the description the shop floor works from, the note, and the
 * quantity rows with their dates — because the copy is made in the browser from
 * exactly this and nothing else. A field missing here is a field that silently
 * does not survive the copy.
 */
public record OrderCopySourceLineItemRow(
        Long id,
        Integer lineOrder,
        Long productId,
        String productName,
        String productCode,
        /** The description the shop floor works from — "opis za radnike". */
        String productDescription,
        String note,
        /** The line's total, as stored on it. */
        Integer quantity,
        List<OrderCopySourceQuantityRow> quantities,
        /** The line's note list, for reading — the copy does not carry these. */
        List<String> notes,
        boolean matched
) {
}
