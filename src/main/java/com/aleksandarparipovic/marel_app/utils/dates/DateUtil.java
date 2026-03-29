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

    public OffsetDateTime buildDateTime(
            LocalDate workDate,
            LocalTime time,
            LocalTime shiftStart,
            ZoneId zone
    ) {
        if (time == null) {
            throw new IllegalArgumentException("Time must not be null");
        }

        LocalDateTime dateTime = LocalDateTime.of(workDate, time);

        if (time.isBefore(shiftStart)) {
            dateTime = dateTime.plusDays(1);
        }

        return dateTime.atZone(zone).toOffsetDateTime();
    }

    public StartEndResult buildStartEnd(
            LocalDate workDate,
            String startTimeStr,
            String endTimeStr,
            LocalTime shiftStart,
            ZoneId zone
    ) {
        LocalTime startTime = parseTime(startTimeStr);
        LocalTime endTime = parseTime(endTimeStr);

        OffsetDateTime start = buildDateTime(workDate, startTime, shiftStart, zone);
        OffsetDateTime end = buildDateTime(workDate, endTime, shiftStart, zone);

        if (end.isBefore(start)) {
            end = end.plusDays(1);
        }

        return new StartEndResult(start, end);
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
