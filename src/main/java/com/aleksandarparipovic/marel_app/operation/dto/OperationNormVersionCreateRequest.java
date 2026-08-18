package com.aleksandarparipovic.marel_app.operation.dto;

import java.time.LocalDate;

/**
 * A norm for an operation.
 *
 * <p>ONE value, not a range. The database keeps a min and a max because the
 * older model had both and payroll still reads the pair, so the service writes
 * the same number into both columns — but the factory works to one norm, and
 * asking for two would be asking a question the shop floor does not have an
 * answer to.
 */
public record OperationNormVersionCreateRequest(
        Integer norm,
        Integer unitsPerProduct,
        LocalDate normDate,
        String note
) {
}
