package com.aleksandarparipovic.marel_app.shift.dto;

import com.aleksandarparipovic.marel_app.shift.EmployeeShiftBreakPeriod;

import java.time.LocalDate;
import java.time.LocalTime;

/** One spell of one of a shift's pauza windows — default or an employee's own. */
public record EmployeeShiftBreakPeriodDto(
        Long id,
        Long shiftId,
        String shiftCode,
        String shiftName,
        Integer breakNo,
        /** True = the employee skips this default break; times are null. */
        boolean suppressed,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate validFrom,
        LocalDate validTo,
        String note
) {
    public static EmployeeShiftBreakPeriodDto from(EmployeeShiftBreakPeriod p) {
        return new EmployeeShiftBreakPeriodDto(
                p.getId(),
                p.getShift().getId(),
                p.getShift().getShiftCode(),
                p.getShift().getName(),
                p.getBreakNo(),
                p.isSuppressed(),
                p.getStartTime(),
                p.getEndTime(),
                p.getValidFrom(),
                p.getValidTo(),
                p.getNote()
        );
    }
}
