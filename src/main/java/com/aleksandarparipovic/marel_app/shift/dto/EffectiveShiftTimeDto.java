package com.aleksandarparipovic.marel_app.shift.dto;

import com.aleksandarparipovic.marel_app.shift.ShiftBreakResolver;

import java.time.LocalTime;
import java.util.List;

/**
 * When each shift runs FOR ONE EMPLOYEE on a date — the picker's answer.
 * {@code employeeOwn} says the hours are the worker's own arrangement rather
 * than the shift's default, so a screen can mark them as such.
 */
public record EffectiveShiftTimeDto(
        Long shiftId,
        String shiftCode,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        boolean employeeOwn,
        /** The pauza windows in force for this person, by breakNo — empty when none. */
        List<EffectiveBreakDto> breaks
) {
    /** One break window; employeeOwn marks the worker's own arrangement. */
    public record EffectiveBreakDto(
            int breakNo,
            LocalTime startTime,
            LocalTime endTime,
            boolean employeeOwn
    ) {
        public static EffectiveBreakDto from(ShiftBreakResolver.ResolvedBreak b) {
            return new EffectiveBreakDto(b.breakNo(), b.startTime(), b.endTime(), b.employeeOwn());
        }
    }
}
