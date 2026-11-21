package com.aleksandarparipovic.marel_app.shift.dto;

import com.aleksandarparipovic.marel_app.shift.EmployeeShiftBreakPeriod;

import java.time.LocalTime;
import java.util.List;

public record ShiftOptionDto(
        Long id,
        String shiftCode,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        /** The shift's CURRENT default pauza windows, by breakNo — empty when none. */
        List<DefaultBreakOptionDto> defaultBreaks
) {
    /** One default break window as a picker needs it. */
    public record DefaultBreakOptionDto(int breakNo, LocalTime startTime, LocalTime endTime) {
        public static DefaultBreakOptionDto from(EmployeeShiftBreakPeriod p) {
            return new DefaultBreakOptionDto(p.getBreakNo(), p.getStartTime(), p.getEndTime());
        }
    }
}
