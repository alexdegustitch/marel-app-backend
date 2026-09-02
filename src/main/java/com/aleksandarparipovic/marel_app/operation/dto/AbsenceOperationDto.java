package com.aleksandarparipovic.marel_app.operation.dto;

/**
 * An operation a whole day off can be drawn with.
 *
 * <p>Carries the product as well as the operation because that is what writing
 * the work log needs: {@code work_logs.operation_id} is NOT NULL and the form
 * sends the product beside it, so the caller must not have to look it up
 * separately for a technical product it never otherwise shows.
 *
 * @param categoryNo the category's CODE — NO or ND — echoed back so the caller
 *                   can be sure it got operations for the category it asked
 *                   about rather than silently drawing the wrong day.
 */
public record AbsenceOperationDto(
        Long id,
        String name,
        Long productId,
        String productName,
        Long workCodeCategoryId,
        String categoryNo
) {
}
