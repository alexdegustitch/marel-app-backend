package com.aleksandarparipovic.marel_app.work_calendar_day.dto;

import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateWorkCalendarDayRequest(
        @NotNull WorkCalendarDayType dayType,
        @Size(max = 255) String label,
        Boolean workingOverride
) {
}
