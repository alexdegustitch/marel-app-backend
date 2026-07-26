package com.aleksandarparipovic.marel_app.work_calendar_day.dto;

import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayType;

import java.time.LocalDate;

public record WorkCalendarDayDto(
        Long id,
        LocalDate calendarDate,
        WorkCalendarDayType dayType,
        String label,
        Boolean workingOverride,
        boolean effectiveWorking
) {
}
