package com.aleksandarparipovic.marel_app.work_calendar_day;

import com.aleksandarparipovic.marel_app.bonus_calendar_sync.BonusCalendarSyncService;
import com.aleksandarparipovic.marel_app.work_calendar_day.dto.UpdateWorkCalendarDayRequest;
import com.aleksandarparipovic.marel_app.work_calendar_day.dto.WorkCalendarDayDto;
import com.aleksandarparipovic.marel_app.work_calendar_day.repository.WorkCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkCalendarDayService {

    private final WorkCalendarDayRepository workCalendarDayRepository;
    private final WorkCalendarDayMapper workCalendarDayMapper;
    private final SerbianHolidayCalculator serbianHolidayCalculator;
    private final BonusCalendarSyncService bonusCalendarSyncService;

    List<WorkCalendarDayDto> getYear(int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        return workCalendarDayRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(from, to)
                .stream()
                .map(workCalendarDayMapper::toDto)
                .toList();
    }

    /**
     * Fills every date in the given year that does not already have a row: holidays
     * (per SerbianHolidayCalculator) as HOLIDAY, Saturdays/Sundays as NON_WORKING,
     * everything else as WORKDAY. Existing rows (including prior manual edits) are
     * left untouched — safe to call again for a year that's already partially filled.
     */
    @Transactional
    public List<WorkCalendarDayDto> autoFillYear(int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);

        Map<LocalDate, String> holidays = serbianHolidayCalculator.getHolidays(year);

        List<WorkCalendarDay> toCreate = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (workCalendarDayRepository.findByCalendarDate(date).isPresent()) {
                continue;
            }

            WorkCalendarDayType dayType;
            String label = null;
            if (holidays.containsKey(date)) {
                dayType = WorkCalendarDayType.HOLIDAY;
                label = holidays.get(date);
            } else if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                dayType = WorkCalendarDayType.NON_WORKING;
            } else {
                dayType = WorkCalendarDayType.WORKDAY;
            }

            toCreate.add(WorkCalendarDay.builder()
                    .calendarDate(date)
                    .dayType(dayType)
                    .label(label)
                    .build());
        }

        workCalendarDayRepository.saveAll(toCreate);
        log.info("[WorkCalendarDayService] Popunjen kalendar rada za {}: {} novih dana (od {} u godini).",
                year, toCreate.size(), from.lengthOfYear());

        bonusCalendarSyncService.syncYear(year);

        return getYear(year);
    }

    @Transactional
    public WorkCalendarDayDto updateDay(LocalDate date, UpdateWorkCalendarDayRequest request) {
        WorkCalendarDay day = workCalendarDayRepository.findByCalendarDate(date)
                .orElseGet(() -> WorkCalendarDay.builder().calendarDate(date).build());

        day.setDayType(request.dayType());
        day.setLabel(request.label());
        day.setWorkingOverride(request.workingOverride());

        WorkCalendarDay saved = workCalendarDayRepository.save(day);

        YearMonth ym = YearMonth.from(date);
        bonusCalendarSyncService.syncMonth(ym.getYear(), ym.getMonthValue());

        return workCalendarDayMapper.toDto(saved);
    }

    private static final long MAX_RANGE_DAYS = 366L * 3;

    /**
     * Applies the same day type/label to every date in [from, to] (inclusive),
     * creating rows for dates that don't have one yet and overwriting existing ones.
     * Capped at 3 years to guard against an accidental unbounded range.
     */
    @Transactional
    public List<WorkCalendarDayDto> updateRange(LocalDate from, LocalDate to, UpdateWorkCalendarDayRequest request) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Početni datum mora biti pre ili jednak krajnjem datumu.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Period je predugačak (maksimalno 3 godine).");
        }

        List<WorkCalendarDay> existing = workCalendarDayRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(from, to);
        Map<LocalDate, WorkCalendarDay> byDate = new HashMap<>();
        existing.forEach(d -> byDate.put(d.getCalendarDate(), d));

        List<WorkCalendarDay> toSave = new ArrayList<>();
        Set<YearMonth> touchedMonths = new LinkedHashSet<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            WorkCalendarDay day = byDate.getOrDefault(date, WorkCalendarDay.builder().calendarDate(date).build());
            day.setDayType(request.dayType());
            day.setLabel(request.label());
            day.setWorkingOverride(request.workingOverride());
            toSave.add(day);
            touchedMonths.add(YearMonth.from(date));
        }

        workCalendarDayRepository.saveAll(toSave);
        log.info("[WorkCalendarDayService] Period {} - {} postavljen na {} ({} dana).",
                from, to, request.dayType(), toSave.size());

        touchedMonths.forEach(ym -> bonusCalendarSyncService.syncMonth(ym.getYear(), ym.getMonthValue()));

        return workCalendarDayRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(from, to)
                .stream()
                .map(workCalendarDayMapper::toDto)
                .toList();
    }
}
