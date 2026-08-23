package com.aleksandarparipovic.marel_app.operation.dto;

import java.time.OffsetDateTime;

/**
 * One entry in the chronology of which norm the operation worked to.
 *
 * <p>{@code until} is derived, not stored: an entry ends where the next one
 * begins. The newest entry ends when its norm was archived, or not at all while
 * that norm is still in force.
 */
public record OperationNormActivationDto(
        Long id,
        Long normVersionId,
        Integer norm,
        OffsetDateTime activatedAt,
        OffsetDateTime until,
        String activatedByName,
        String reason,
        /** ADDED, EDITED, SUCCEEDED, ACTIVATED or MIGRATED. */
        String source
) {
}
