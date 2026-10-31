package com.aleksandarparipovic.marel_app.admin_schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static com.aleksandarparipovic.marel_app.admin_schedule.AdminScheduleService.windowOutsideShift;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overlap rule for a roster entry's od–do window: part of the interval
 * inside the shift is enough (the owner's rule), and only an interval ENTIRELY
 * outside the shift is refused — that time belongs to some other shift.
 * Counted on the clock circle so shift III and a window across midnight both
 * read correctly.
 */
class AdminScheduleWindowRuleTest {

    private static final LocalTime T06 = LocalTime.of(6, 0);
    private static final LocalTime T14 = LocalTime.of(14, 0);
    private static final LocalTime T22 = LocalTime.of(22, 0);

    @Test
    @DisplayName("a day shift accepts its own hours and any stretch of them")
    void dayShiftContainment() {
        assertThat(windowOutsideShift(T06, T14, T06, T14)).isFalse();
        assertThat(windowOutsideShift(T06, T14, T06, LocalTime.of(8, 0))).isFalse();
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(9, 30), LocalTime.of(12, 0))).isFalse();
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(12, 0), T14)).isFalse();
    }

    @Test
    @DisplayName("a day shift accepts a window that only PARTLY falls into it")
    void dayShiftPartialOverlap() {
        // Starts before the shift, runs late past its end, wraps midnight into
        // its morning — each shares real minutes with 06–14, so each is legal.
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(5, 0), LocalTime.of(7, 0))).isFalse();
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(12, 0), LocalTime.of(15, 0))).isFalse();
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(23, 0), LocalTime.of(7, 0))).isFalse();
    }

    @Test
    @DisplayName("a day shift refuses only an interval that misses it entirely")
    void dayShiftRefusals() {
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(15, 0), LocalTime.of(16, 0))).isTrue();
        // Starting exactly at the shift's end shares no minute with it.
        assertThat(windowOutsideShift(T06, T14, T14, LocalTime.of(15, 0))).isTrue();
        // Ending exactly at the shift's start shares none either.
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(23, 0), LocalTime.of(5, 0))).isTrue();
        assertThat(windowOutsideShift(T06, T14, LocalTime.of(23, 0), T06)).isTrue();
    }

    @Test
    @DisplayName("the night shift counts across midnight")
    void nightShiftContainment() {
        assertThat(windowOutsideShift(T22, T06, T22, T06)).isFalse();
        assertThat(windowOutsideShift(T22, T06, T22, LocalTime.of(23, 30))).isFalse();
        assertThat(windowOutsideShift(T22, T06, LocalTime.of(23, 0), LocalTime.of(1, 0))).isFalse();
        assertThat(windowOutsideShift(T22, T06, LocalTime.of(2, 0), T06)).isFalse();
    }

    @Test
    @DisplayName("the night shift accepts partial overlap on either edge")
    void nightShiftPartialOverlap() {
        // 21–23 catches the shift's first hour; 05–07 its last.
        assertThat(windowOutsideShift(T22, T06, LocalTime.of(21, 0), LocalTime.of(23, 0))).isFalse();
        assertThat(windowOutsideShift(T22, T06, LocalTime.of(5, 0), LocalTime.of(7, 0))).isFalse();
    }

    @Test
    @DisplayName("the night shift still refuses an interval wholly outside it")
    void nightShiftRefusals() {
        assertThat(windowOutsideShift(T22, T06, LocalTime.of(10, 0), LocalTime.of(12, 0))).isTrue();
        // Daytime between its end and its start, edges included.
        assertThat(windowOutsideShift(T22, T06, T06, LocalTime.of(8, 0))).isTrue();
        assertThat(windowOutsideShift(T22, T06, LocalTime.of(14, 0), T22)).isTrue();
    }
}
