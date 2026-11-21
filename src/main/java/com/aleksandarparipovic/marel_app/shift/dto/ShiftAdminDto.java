package com.aleksandarparipovic.marel_app.shift.dto;

import com.aleksandarparipovic.marel_app.shift.EmployeeShiftBreakPeriod;
import com.aleksandarparipovic.marel_app.shift.Shift;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

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
        /** The shift's CURRENT default pauza windows, by breakNo — empty when none. */
        List<DefaultBreakDto> breaks,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    /** One open default break of the shift. */
    public record DefaultBreakDto(
            int breakNo,
            LocalTime startTime,
            LocalTime endTime,
            /** Since when this window is in force. */
            LocalDate since
    ) {
        public static DefaultBreakDto from(EmployeeShiftBreakPeriod p) {
            return new DefaultBreakDto(p.getBreakNo(), p.getStartTime(), p.getEndTime(), p.getValidFrom());
        }
    }

    public static ShiftAdminDto from(Shift s, LocalDate defaultSince) {
        return from(s, defaultSince, List.of());
    }

    public static ShiftAdminDto from(Shift s, LocalDate defaultSince, List<EmployeeShiftBreakPeriod> openBreaks) {
        return new ShiftAdminDto(
                s.getId(),
                s.getShiftCode(),
                s.getName(),
                s.getStartTime(),
                s.getEndTime(),
                s.getIsActive(),
                defaultSince,
                openBreaks.stream().map(DefaultBreakDto::from).toList(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
