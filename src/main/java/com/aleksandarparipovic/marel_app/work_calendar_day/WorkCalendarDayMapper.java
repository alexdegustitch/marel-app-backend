package com.aleksandarparipovic.marel_app.work_calendar_day;

import com.aleksandarparipovic.marel_app.work_calendar_day.dto.WorkCalendarDayDto;
import org.springframework.stereotype.Component;

@Component
public class WorkCalendarDayMapper {

    WorkCalendarDayDto toDto(WorkCalendarDay day) {
        return new WorkCalendarDayDto(
                day.getId(),
                day.getCalendarDate(),
                day.getDayType(),
                day.getLabel(),
                day.getWorkingOverride(),
                WorkCalendarDayEffectiveStatus.isWorking(day)
        );
    }
}
