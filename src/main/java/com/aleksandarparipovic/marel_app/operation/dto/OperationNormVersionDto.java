package com.aleksandarparipovic.marel_app.operation.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One entry in an operation's norm history, as the screen reads it. */
public record OperationNormVersionDto(
        Long id,
        Integer minNorm,
        Integer maxNorm,
        Integer unitsPerProduct,
        LocalDate normDate,
        String note,
        OffsetDateTime createdAt,
        String createdByName,
        OffsetDateTime verifiedAt,
        String verifiedByName,
        /** True for the version currently in force (the newest one). */
        boolean current
) {
}
