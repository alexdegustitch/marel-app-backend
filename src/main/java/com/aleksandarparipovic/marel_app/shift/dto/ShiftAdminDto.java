package com.aleksandarparipovic.marel_app.shift.dto;

import com.aleksandarparipovic.marel_app.shift.EmployeeShiftBreakPeriod;
import com.aleksandarparipovic.marel_app.shift.Shift;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** One shift as the šifarnik reads it. */
public record ShiftAdminDto(
        Long id,
        String shiftCode,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        Boolean isActive,
        /** Since when the CURRENT default hours are in force (open default period). */
        LocalDate defaultSince,
        /** The shift's CURRENT default pauza window — all three null when none is configured. */
        LocalTime breakStartTime,
        LocalTime breakEndTime,
        LocalDate breakSince,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ShiftAdminDto from(Shift s, LocalDate defaultSince) {
        return from(s, defaultSince, null);
    }

    public static ShiftAdminDto from(Shift s, LocalDate defaultSince, EmployeeShiftBreakPeriod openBreak) {
        return new ShiftAdminDto(
                s.getId(),
                s.getShiftCode(),
                s.getName(),
                s.getStartTime(),
                s.getEndTime(),
                s.getIsActive(),
                defaultSince,
                openBreak == null ? null : openBreak.getStartTime(),
                openBreak == null ? null : openBreak.getEndTime(),
                openBreak == null ? null : openBreak.getValidFrom(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
