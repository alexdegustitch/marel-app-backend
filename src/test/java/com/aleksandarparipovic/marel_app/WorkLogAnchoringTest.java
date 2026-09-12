package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.utils.dates.DateUtil;
import com.aleksandarparipovic.marel_app.utils.dto.StartEndResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which DAY a log's clock times land on is decided by closeness to the shift's
 * window, not by "before the shift's start clock means tomorrow".
 *
 * <p>The old rule was written for night shifts (a 02:00 entry belongs after
 * midnight) but misfired on day shifts: a 10:00–15:00 entry on a 14:00–22:00
 * shift landed on the NEXT day, and the boundary recalculation then stretched
 * the shift across two dates — the karton showed "14:00 – 15:00" for an
 * eight-hour day.
 */
class WorkLogAnchoringTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 2);

    private final DateUtil dateUtil = new DateUtil();

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(ZONE).toOffsetDateTime();
    }

    /** Second shift 14:00–22:00, same day. */
    private StartEndResult onDayShift(String start, String end) {
        return dateUtil.buildStartEnd(DAY, start, end, at(DAY, 14, 0), at(DAY, 22, 0), ZONE);
    }

    /** Third shift 22:00 → 06:00 next day. */
    private StartEndResult onNightShift(String start, String end) {
        return dateUtil.buildStartEnd(DAY, start, end, at(DAY, 22, 0), at(DAY.plusDays(1), 6, 0), ZONE);
    }

    @Test
    void entryBeforeADayShiftStaysOnTheWorkDate() {
        StartEndResult time = onDayShift("10:00", "15:00");
        assertThat(time.start()).isEqualTo(at(DAY, 10, 0));
        assertThat(time.end()).isEqualTo(at(DAY, 15, 0));
    }

    @Test
    void entryTouchingTheDayShiftStartStaysOnTheWorkDate() {
        StartEndResult time = onDayShift("12:00", "14:00");
        assertThat(time.start()).isEqualTo(at(DAY, 12, 0));
        assertThat(time.end()).isEqualTo(at(DAY, 14, 0));
    }

    @Test
    void entryCrossingMidnightAfterADayShiftEndsTomorrow() {
        StartEndResult time = onDayShift("22:00", "00:00");
        assertThat(time.start()).isEqualTo(at(DAY, 22, 0));
        assertThat(time.end()).isEqualTo(at(DAY.plusDays(1), 0, 0));
    }

    @Test
    void entryInsideTheDayShiftIsUntouched() {
        StartEndResult time = onDayShift("15:00", "18:00");
        assertThat(time.start()).isEqualTo(at(DAY, 15, 0));
        assertThat(time.end()).isEqualTo(at(DAY, 18, 0));
    }

    @Test
    void afterMidnightEntryOnANightShiftBelongsToTheNextDay() {
        StartEndResult time = onNightShift("02:00", "05:00");
        assertThat(time.start()).isEqualTo(at(DAY.plusDays(1), 2, 0));
        assertThat(time.end()).isEqualTo(at(DAY.plusDays(1), 5, 0));
    }

    @Test
    void nightShiftEntryCrossingMidnightStartsOnTheWorkDate() {
        StartEndResult time = onNightShift("23:00", "01:00");
        assertThat(time.start()).isEqualTo(at(DAY, 23, 0));
        assertThat(time.end()).isEqualTo(at(DAY.plusDays(1), 1, 0));
    }

    @Test
    void entryBeforeANightShiftStaysOnTheWorkDate() {
        StartEndResult time = onNightShift("20:00", "22:00");
        assertThat(time.start()).isEqualTo(at(DAY, 20, 0));
        assertThat(time.end()).isEqualTo(at(DAY, 22, 0));
    }

    @Test
    void entryAfterANightShiftEndFollowsTheShiftIntoTheNextDay() {
        StartEndResult time = onNightShift("06:00", "08:00");
        assertThat(time.start()).isEqualTo(at(DAY.plusDays(1), 6, 0));
        assertThat(time.end()).isEqualTo(at(DAY.plusDays(1), 8, 0));
    }
}
