package com.aleksandarparipovic.marel_app.dashboard.dto;

import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.MissingShiftRow;

import java.time.LocalDate;
import java.util.List;

/**
 * The full answer behind the board's "neunete smene" card: who is employed on
 * the day and has nothing entered for it. Read live when the drawer opens —
 * this is a worklist someone acts on row by row, and a colleague may have just
 * entered one of the shifts.
 *
 * @param applicable false on Sunday, when shifts are not required. The rows are
 *                   then empty because the question does not apply, not because
 *                   everyone is entered — the screen must say which.
 */
public record MissingShiftsResponse(
        LocalDate date,
        boolean applicable,
        long total,
        List<MissingShiftRow> rows
) {
}
