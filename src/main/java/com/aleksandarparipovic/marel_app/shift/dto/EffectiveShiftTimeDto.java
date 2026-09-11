package com.aleksandarparipovic.marel_app.shift.dto;

import java.time.LocalTime;

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
        boolean employeeOwn
) {}
