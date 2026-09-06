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
                WorkCalendarDayEffectiveStatus.isWorking(day),
                // updated_at is set by a DB trigger; on a just-saved entity the
                // in-memory value is stale, but every write path is followed by
                // a re-read on the client, so only the GET value is ever shown.
                day.getUpdatedAt()
        );
    }
}
