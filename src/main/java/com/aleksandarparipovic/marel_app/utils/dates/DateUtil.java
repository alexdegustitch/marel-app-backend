package com.aleksandarparipovic.marel_app.utils.dates;

import com.aleksandarparipovic.marel_app.utils.dto.StartEndResult;
import org.springframework.stereotype.Component;

import java.time.*;

@Component
public class DateUtil {

    public OffsetDateTime parseOffsetDateTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return OffsetDateTime.parse(value);
    }

    public LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) {
            throw new IllegalArgumentException("Time must not be empty");
        }

        try {
            return LocalTime.parse(time);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid time format, expected HH:mm");
        }
    }

    /**
     * Anchor a log's HH:mm times to real datetimes around the shift's window.
     *
     * <p>The clock alone cannot say which day an entry belongs to, so the shift
     * window decides: the interval is tried on the work date and again a day
     * later, and the candidate closer to the window wins (overlapping or
     * touching it counts as distance zero; a tie stays on the work date). This
     * is what puts a night shift's 02:00 entry after midnight — and what the
     * old rule "any time before the shift's start clock is tomorrow" got wrong
     * for day shifts: a 10:00–15:00 entry on a 14:00–22:00 shift landed on the
     * NEXT day, and the boundary recalculation then stretched the shift across
     * two dates.
     */
    public StartEndResult buildStartEnd(
            LocalDate workDate,
            String startTimeStr,
            String endTimeStr,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd,
            ZoneId zone
    ) {
        LocalTime startTime = parseTime(startTimeStr);
        LocalTime endTime = parseTime(endTimeStr);

        OffsetDateTime start = LocalDateTime.of(workDate, startTime).atZone(zone).toOffsetDateTime();
        OffsetDateTime end = LocalDateTime.of(workDate, endTime).atZone(zone).toOffsetDateTime();
        // An end at or before the start crosses midnight (22:00–00:30).
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }

        long sameDayGap = gapToWindowMinutes(start, end, shiftStart, shiftEnd);
        if (sameDayGap > 0
                && gapToWindowMinutes(start.plusDays(1), end.plusDays(1), shiftStart, shiftEnd) < sameDayGap) {
            return new StartEndResult(start.plusDays(1), end.plusDays(1));
        }
        return new StartEndResult(start, end);
    }

    /** Minutes between an interval and the shift window; 0 when they overlap or touch. */
    private static long gapToWindowMinutes(
            OffsetDateTime start,
            OffsetDateTime end,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd
    ) {
        if (!start.isAfter(shiftEnd) && !shiftStart.isAfter(end)) {
            return 0;
        }
        if (end.isBefore(shiftStart)) {
            return Duration.between(end, shiftStart).toMinutes();
        }
        return Duration.between(shiftEnd, start).toMinutes();
    }

    public void validateWithinShift(
            OffsetDateTime start,
            OffsetDateTime end,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd
    ) {
        if (start.isBefore(shiftStart)) {
            throw new IllegalArgumentException("Start time is before shift start");
        }

        if (end.isAfter(shiftEnd)) {
            throw new IllegalArgumentException("End time is after shift end");
        }

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("End must be after start");
        }
    }
}
