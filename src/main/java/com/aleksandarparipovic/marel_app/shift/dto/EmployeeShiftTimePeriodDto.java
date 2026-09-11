package com.aleksandarparipovic.marel_app.shift.dto;

import com.aleksandarparipovic.marel_app.shift.EmployeeShiftTimePeriod;

import java.time.LocalDate;
import java.time.LocalTime;

/** One spell of an employee's own hours for a shift, as the screen reads it. */
public record EmployeeShiftTimePeriodDto(
        Long id,
        Long shiftId,
        String shiftCode,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate validFrom,
        LocalDate validTo,
        String note
) {
    public static EmployeeShiftTimePeriodDto from(EmployeeShiftTimePeriod p) {
        return new EmployeeShiftTimePeriodDto(
                p.getId(),
                p.getShift().getId(),
                p.getShift().getShiftCode(),
                p.getShift().getName(),
                p.getStartTime(),
                p.getEndTime(),
                p.getValidFrom(),
                p.getValidTo(),
                p.getNote()
        );
    }
}
