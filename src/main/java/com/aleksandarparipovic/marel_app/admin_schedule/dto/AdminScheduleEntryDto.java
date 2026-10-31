package com.aleksandarparipovic.marel_app.admin_schedule.dto;

import com.aleksandarparipovic.marel_app.admin_schedule.AdminScheduleEntry;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One roster cell's worth of data. The shift's own hours ride along so the
 * screen can show "I · 06:00–14:00" without a second lookup; {@code startTime}
 * and {@code endTime} are the entry's OWN window and stay null for a whole
 * shift.
 */
public record AdminScheduleEntryDto(
        Long id,
        LocalDate workDate,
        Long userId,
        String userFullName,
        Long shiftId,
        String shiftCode,
        String shiftName,
        LocalTime shiftStartTime,
        LocalTime shiftEndTime,
        LocalTime startTime,
        LocalTime endTime,
        String note
) {
    public static AdminScheduleEntryDto from(AdminScheduleEntry e) {
        return new AdminScheduleEntryDto(
                e.getId(),
                e.getWorkDate(),
                e.getUser().getId(),
                e.getUser().getFullName(),
                e.getShift().getId(),
                e.getShift().getShiftCode(),
                e.getShift().getName(),
                e.getShift().getStartTime(),
                e.getShift().getEndTime(),
                e.getStartTime(),
                e.getEndTime(),
                e.getNote()
        );
    }
}
