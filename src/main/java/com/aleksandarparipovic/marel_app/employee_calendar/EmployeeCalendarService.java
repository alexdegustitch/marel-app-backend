package com.aleksandarparipovic.marel_app.employee_calendar;

import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.employee_calendar.dto.EmployeeCalendarDtos.*;
import com.aleksandarparipovic.marel_app.employee_leave.EmployeeLeaveService;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDay;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayEffectiveStatus;
import com.aleksandarparipovic.marel_app.work_calendar_day.repository.WorkCalendarDayRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The worker's calendar page, in one answer.
 *
 * <p>Reads the live shifts (withdrawn ones excluded), their daily reports, the
 * work calendar and the month's payroll standing, and sums everything HERE:
 * the figures on this screen and the ones in the karton come from the same
 * daily reports, so the two can never show a different month.
 */
@Service
@RequiredArgsConstructor
public class EmployeeCalendarService {

    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_LOCKED = "LOCKED";

    private final WorkShiftRepository workShiftRepository;
    private final DailyReportRepository dailyReportRepository;
    private final WorkCalendarDayRepository workCalendarDayRepository;
    private final EmployeeRecordService employeeRecordService;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final EmployeeLeaveService employeeLeaveService;

    @Transactional(readOnly = true)
    public EmployeeCalendarResponse month(Long employeeId, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        List<WorkShift> shifts =
                workShiftRepository.findActiveWithCategoryInRange(employeeId, monthStart, monthEnd);

        // Daily reports joined in Java rather than SQL: a month is at most a few
        // dozen rows on either side, and both lists are one query each.
        Map<Long, DailyReport> reportByShiftId = new LinkedHashMap<>();
        for (DailyReport report : dailyReportRepository
                .findByEmployee_IdAndWorkDateBetween(employeeId, monthStart, monthEnd)) {
            if (report.getWorkShift() != null) {
                reportByShiftId.put(report.getWorkShift().getId(), report);
            }
        }

        Map<LocalDate, WorkCalendarDay> calByDate = new LinkedHashMap<>();
        for (WorkCalendarDay day : workCalendarDayRepository
                .findByCalendarDateBetweenOrderByCalendarDateAsc(monthStart, monthEnd)) {
            calByDate.put(day.getCalendarDate(), day);
        }

        Map<LocalDate, List<CalendarShift>> shiftsByDate = new LinkedHashMap<>();
        for (WorkShift shift : shifts) {
            shiftsByDate.computeIfAbsent(shift.getWorkDate(), d -> new ArrayList<>())
                    .add(toCalendarShift(shift, reportByShiftId.get(shift.getId())));
        }

        List<CalendarDay> days = new ArrayList<>(monthEnd.getDayOfMonth());
        for (LocalDate d = monthStart; !d.isAfter(monthEnd); d = d.plusDays(1)) {
            WorkCalendarDay calDay = calByDate.get(d);
            boolean working = calDay != null
                    ? WorkCalendarDayEffectiveStatus.isWorking(calDay)
                    : d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
            days.add(new CalendarDay(
                    d,
                    calDay != null ? calDay.getDayType().name() : null,
                    holidayLabelOf(calDay),
                    working,
                    shiftsByDate.getOrDefault(d, List.of())));
        }

        return new EmployeeCalendarResponse(
                year,
                month,
                employeeRecordService.findRecordIdForEmployeeAndMonth(employeeId, year, month).orElse(null),
                payrollStatus(employeeId, monthStart),
                summarize(shiftsByDate),
                days,
                employeeLeaveService.periodsForMonth(employeeId, year, month));
    }

    private CalendarShift toCalendarShift(WorkShift shift, DailyReport report) {
        WorkCodeCategory category = shift.getWorkCodeCategory();
        return new CalendarShift(
                shift.getId(),
                shift.getShift() != null ? shift.getShift().getShiftCode() : null,
                shift.getShift() != null ? shift.getShift().getName() : null,
                shift.getStartAt(),
                shift.getEndAt(),
                category != null ? category.getId() : null,
                category != null ? category.getCategoryNo() : null,
                category != null ? category.getCategoryName() : null,
                category != null ? category.getType() : null,
                category != null ? category.getSickLeaveKind() : null,
                category != null && Boolean.TRUE.equals(category.getIsFullDay()),
                report != null ? report.getTotalShiftMinutes() : null,
                report != null ? report.getTotalApprovedMinutes() : null,
                report != null ? report.getApprovedPerformanceRate() : null,
                report != null ? report.getEffectiveMealsCount() : null);
    }

    /**
     * The strip under the grid. "Hours at work" sums WORK days only — a day of
     * sick leave is time away, and it is already stated in {@code absenceDays};
     * the approved hours and the performance average likewise read the worked
     * days, which is what those figures mean everywhere else in the app.
     */
    private CalendarSummary summarize(Map<LocalDate, List<CalendarShift>> shiftsByDate) {
        long workedDays = 0;
        int totalShiftMinutes = 0;
        int totalApprovedMinutes = 0;
        int meals = 0;
        BigDecimal weightedRate = BigDecimal.ZERO;
        long rateWeight = 0;
        Map<String, AbsenceDaysEntry> absenceDays = new LinkedHashMap<>();

        for (Map.Entry<LocalDate, List<CalendarShift>> entry : shiftsByDate.entrySet()) {
            boolean dayWorked = false;
            for (CalendarShift shift : entry.getValue()) {
                boolean absenceLike = "ABSENCE".equals(shift.categoryType())
                        || "SICK_LEAVE".equals(shift.categoryType());
                if (absenceLike) {
                    absenceDays.merge(
                            shift.categoryNo(),
                            new AbsenceDaysEntry(shift.categoryNo(), shift.categoryName(),
                                    shift.categoryType(), 1),
                            (a, b) -> new AbsenceDaysEntry(a.categoryNo(), a.categoryName(),
                                    a.categoryType(), a.days() + b.days()));
                    continue;
                }
                dayWorked = true;
                totalShiftMinutes += safe(shift.totalShiftMinutes());
                totalApprovedMinutes += safe(shift.totalApprovedMinutes());
                meals += safe(shift.effectiveMealsCount());
                if (shift.approvedPerformanceRate() != null && safe(shift.totalShiftMinutes()) > 0) {
                    weightedRate = weightedRate.add(shift.approvedPerformanceRate()
                            .multiply(BigDecimal.valueOf(shift.totalShiftMinutes())));
                    rateWeight += shift.totalShiftMinutes();
                }
            }
            if (dayWorked) {
                workedDays++;
            }
        }

        BigDecimal avgRate = rateWeight > 0
                ? weightedRate.divide(BigDecimal.valueOf(rateWeight), 1, RoundingMode.HALF_UP)
                : null;

        return new CalendarSummary(workedDays, totalShiftMinutes, totalApprovedMinutes,
                avgRate, meals, List.copyOf(absenceDays.values()));
    }

    private PayrollStatus payrollStatus(Long employeeId, LocalDate monthStart) {
        PayrollRunItem item = payrollRunItemRepository
                .findByEmployee_IdAndPeriod(employeeId, monthStart)
                .stream().findFirst().orElse(null);
        if (item == null) {
            return null;
        }
        boolean closed = STATUS_APPROVED.equals(item.getStatus()) || STATUS_LOCKED.equals(item.getStatus());
        return new PayrollStatus(item.getId(), item.getStatus(), closed);
    }

    private static String holidayLabelOf(WorkCalendarDay calDay) {
        if (calDay == null) {
            return null;
        }
        return switch (calDay.getDayType()) {
            case HOLIDAY -> calDay.getLabel() != null ? calDay.getLabel() : "Praznik";
            case COLLECTIVE_LEAVE -> calDay.getLabel() != null ? calDay.getLabel() : "Kolektivni odmor";
            default -> null;
        };
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
