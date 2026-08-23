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
        /** True for the norm in force — stated on the version, not inferred from the order. */
        boolean current,
        /** A norm deliberately entered with no date; the date column reads "Privremena". */
        boolean temporary,
        /** Set once the norm is archived. Archived norms are history, still readable. */
        OffsetDateTime archivedAt,
        /** When this version was last put in force, and by whom. */
        OffsetDateTime activatedAt,
        String activatedByName
) {
}
