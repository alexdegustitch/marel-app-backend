package com.aleksandarparipovic.marel_app.employee_calendar.dto;

import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.LeavePeriodDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One month of one worker, ready to draw: every day of the month with the work
 * calendar's word on it, every live shift with its category and figures, and
 * the month's sums — all resolved HERE, so the screen never computes a number.
 */
public final class EmployeeCalendarDtos {

    private EmployeeCalendarDtos() {
    }

    /** One live shift on one day, with the daily report's figures beside it. */
    public record CalendarShift(
            Long workShiftId,
            String shiftCode,
            String shiftName,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Long categoryId,
            String categoryNo,
            String categoryName,
            /** WORK | ABSENCE | SICK_LEAVE — what kind of time the day holds. */
            String categoryType,
            String sickLeaveKind,
            /** A whole-day absence — the calendar draws it without shift times. */
            boolean fullDay,
            Integer totalShiftMinutes,
            Integer totalApprovedMinutes,
            BigDecimal approvedPerformanceRate,
            /** The count the month PAYS: computed plus the manual correction. */
            Integer effectiveMealsCount
    ) {
    }

    /**
     * One calendar day. {@code dayType}/{@code holidayLabel} come from the work
     * calendar read LIVE — nothing is written into the karton for them.
     */
    public record CalendarDay(
            LocalDate date,
            String dayType,
            String holidayLabel,
            boolean effectiveWorking,
            List<CalendarShift> shifts
    ) {
    }

    /** How many days of the month one absence-type category took. */
    public record AbsenceDaysEntry(String categoryNo, String categoryName, String categoryType, long days) {
    }

    public record CalendarSummary(
            long workedDays,
            int totalShiftMinutes,
            int totalApprovedMinutes,
            BigDecimal avgApprovedPerformanceRate,
            int meals,
            List<AbsenceDaysEntry> absenceDays
    ) {
    }

    /** The month's payroll standing — what decides whether entry is possible. */
    public record PayrollStatus(Long payrollRunItemId, String status, boolean closed) {
    }

    public record EmployeeCalendarResponse(
            int year,
            int month,
            Long employeeRecordId,
            PayrollStatus payroll,
            CalendarSummary summary,
            List<CalendarDay> days,
            List<LeavePeriodDto> leavePeriods
    ) {
    }
}
