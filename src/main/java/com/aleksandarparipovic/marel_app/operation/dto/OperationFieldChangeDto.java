package com.aleksandarparipovic.marel_app.operation.dto;

import java.time.OffsetDateTime;

/**
 * One recorded change of an operation attribute — who turned which value into
 * which, and when. Read out of {@code operations_history}, the trigger-fed
 * old/new snapshot of every operations UPDATE, so the history reaches back to
 * the day the trigger was installed and nobody has to remember to write it.
 */
public record OperationFieldChangeDto(
        /** Which attribute changed: {@code CATEGORY} or {@code UNITS}. */
        String field,
        OffsetDateTime changedAt,
        Long changedById,
        /** Null when the change came from a job rather than a signed-in user. */
        String changedByName,
        /** Display value before the change — null means the field was empty. */
        String oldValue,
        String newValue
) {
    public static final String FIELD_CATEGORY = "CATEGORY";
    public static final String FIELD_UNITS = "UNITS";
}
