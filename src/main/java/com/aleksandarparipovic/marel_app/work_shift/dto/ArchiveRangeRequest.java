package com.aleksandarparipovic.marel_app.work_shift.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Withdraw every live shift of one employee in a date range, signed with the
 * caller's password — a whole stretch of somebody's month disappears from the
 * reckoning, which is too heavy for a plain click.
 */
public record ArchiveRangeRequest(
        @NotNull Long employeeId,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        @NotBlank(message = "Lozinka je obavezna.") String password,
        String reason
) {
}
