package com.aleksandarparipovic.marel_app.employee_bonus.dto;

import java.util.List;

/**
 * What the change did, including what it could NOT do.
 *
 * <p>{@code message} is meant to be shown as-is. A retroactive change can leave
 * locked months untouched, and the screen has to say which — the edit succeeded
 * either way, so this is not an error.
 */
public record ChangeBonusResponse(
        List<String> recalculatedMonths,
        List<String> skippedLockedMonths,
        String message
) {
}
