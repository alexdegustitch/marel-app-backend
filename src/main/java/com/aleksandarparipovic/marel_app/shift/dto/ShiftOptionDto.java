package com.aleksandarparipovic.marel_app.shift.dto;

import java.time.LocalTime;

public record ShiftOptionDto(
        Long id,
        String shiftCode,
        String name,
        LocalTime startTime,
        LocalTime endTime
) {}

