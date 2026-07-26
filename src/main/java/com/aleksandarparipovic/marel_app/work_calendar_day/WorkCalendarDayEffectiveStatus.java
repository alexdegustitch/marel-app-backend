package com.aleksandarparipovic.marel_app.work_calendar_day;

import java.time.DayOfWeek;

public final class WorkCalendarDayEffectiveStatus {

    private WorkCalendarDayEffectiveStatus() {
    }

    /**
     * General "is this day worked" check: manual override wins, otherwise
     * only WORKDAY counts as working.
     */
    public static boolean isWorking(WorkCalendarDay day) {
        if (day.getWorkingOverride() != null) {
            return day.getWorkingOverride();
        }
        return day.getDayType() == WorkCalendarDayType.WORKDAY;
    }

    /**
     * Bonus-calculation specific check: a Saturday defaults to "working"
     * (the auto-fill marks every Saturday as NON_WORKING, but this business
     * normally works Saturdays), unless it's a HOLIDAY/COLLECTIVE_LEAVE or
     * explicitly overridden off. Manual override always wins either way.
     */
    public static boolean isWorkingForBonusPurposes(WorkCalendarDay day) {
        if (day.getWorkingOverride() != null) {
            return day.getWorkingOverride();
        }
        if (day.getDayType() == WorkCalendarDayType.WORKDAY) {
            return true;
        }
        return day.getDayType() == WorkCalendarDayType.NON_WORKING
                && day.getCalendarDate().getDayOfWeek() == DayOfWeek.SATURDAY;
    }
}
