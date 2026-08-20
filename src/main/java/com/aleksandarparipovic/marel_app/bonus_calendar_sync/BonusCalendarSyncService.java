package com.aleksandarparipovic.marel_app.bonus_calendar_sync;

import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRule;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRuleRepository;
import com.aleksandarparipovic.marel_app.bonus_eligibility_rules.BonusEligibilityRuleService;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursHistoryRecorder;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRule;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleHistory;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleRepository;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.dto.BonusMinHoursRuleResponse;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDay;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayEffectiveStatus;
import com.aleksandarparipovic.marel_app.work_calendar_day.repository.WorkCalendarDayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Keeps bonus-related derived data (min hours for bonus, and which Saturday-of-month
 * eligibility rows are actually worked) in sync with the work calendar. Every day/range
 * edit and every year auto-fill funnels through here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BonusCalendarSyncService {

    private final WorkCalendarDayRepository workCalendarDayRepository;
    private final BonusMinHoursRuleRepository bonusMinHoursRuleRepository;
    private final BonusMinHoursHistoryRecorder historyRecorder;
    private final BonusEligibilityRuleRepository bonusEligibilityRuleRepository;
    private final BonusEligibilityRuleService bonusEligibilityRuleService;
    private final PayrollRunItemRepository payrollRunItemRepository;

    @Transactional
    public void syncMonth(int year, int month) {
        LocalDate period = YearMonth.of(year, month).atDay(1);
        List<WorkCalendarDay> days = workCalendarDayRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(
                period, period.withDayOfMonth(period.lengthOfMonth()));

        if (days.isEmpty()) {
            return;
        }

        int minHours = computeMinHours(days);

        BonusMinHoursRule rule = bonusMinHoursRuleRepository.findByPeriodAndArchivedAtIsNull(period)
                .orElseGet(() -> BonusMinHoursRule.builder().period(period).build());
        /*
         * The calendar owns its own number and nothing else. A manual override stays exactly
         * where it is — that is what makes it an override rather than a suggestion the next
         * calendar edit quietly undoes — and the effective value the database derives keeps
         * preferring it. What changes here still matters even while overridden: it is what
         * "reset" will return the month to, and what the screen shows struck through.
         */
        rule.setMinNumHours(minHours);
        BonusMinHoursRule saved = bonusMinHoursRuleRepository.saveAndFlush(rule);

        historyRecorder.record(period, minHours, saved.getManualMinNumHours(),
                BonusMinHoursRuleHistory.Source.CALENDAR_SYNC, null,
                "Izmena kalendara rada.");

        syncEligibilityActiveFlags(year, month, days);

        payrollRunItemRepository.markNeedsRecalculationByYearAndMonth(year, month);

        log.info("[BonusCalendarSyncService] Sinhronizovan mesec {}/{}: sistemski min sati = {}{}.",
                month, year, minHours,
                saved.getManualMinNumHours() == null
                        ? ""
                        : ", ručno postavljeno " + saved.getManualMinNumHours() + " i dalje važi");
    }

    @Transactional
    public void syncYear(int year) {
        for (int month = 1; month <= 12; month++) {
            syncMonth(year, month);
        }
    }

    @Transactional
    public List<BonusMinHoursRuleResponse> initYear(int year) {
        List<BonusMinHoursRuleResponse> result = new ArrayList<>();

        for (int month = 1; month <= 12; month++) {
            LocalDate period = YearMonth.of(year, month).atDay(1);

            var existing = bonusMinHoursRuleRepository.findByPeriodAndArchivedAtIsNull(period);
            if (existing.isPresent()) {
                result.add(new BonusMinHoursRuleResponse(existing.get()));
                continue;
            }

            List<WorkCalendarDay> days = workCalendarDayRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(
                    period, period.withDayOfMonth(period.lengthOfMonth()));

            int minHours = days.isEmpty() ? 0 : computeMinHours(days);

            BonusMinHoursRule rule = BonusMinHoursRule.builder().period(period).minNumHours(minHours).build();
            BonusMinHoursRule saved = bonusMinHoursRuleRepository.saveAndFlush(rule);

            historyRecorder.record(period, minHours, null,
                    BonusMinHoursRuleHistory.Source.CALENDAR_SYNC, null,
                    "Inicijalizacija godine.");

            result.add(new BonusMinHoursRuleResponse(saved));
        }

        return result;
    }

    private record MonthWorkStats(int workdays, int workingSaturdays) {
    }

    private MonthWorkStats computeMonthWorkStats(List<WorkCalendarDay> days) {
        int workdays = 0;
        int workingSaturdays = 0;

        for (WorkCalendarDay day : days) {
            DayOfWeek dayOfWeek = day.getCalendarDate().getDayOfWeek();
            if (dayOfWeek == DayOfWeek.SATURDAY) {
                if (WorkCalendarDayEffectiveStatus.isWorkingForBonusPurposes(day)) {
                    workingSaturdays++;
                }
            } else if (dayOfWeek != DayOfWeek.SUNDAY) {
                if (WorkCalendarDayEffectiveStatus.isWorking(day)) {
                    workdays++;
                }
            }
        }

        return new MonthWorkStats(workdays, workingSaturdays);
    }

    private int computeMinHours(List<WorkCalendarDay> days) {
        MonthWorkStats stats = computeMonthWorkStats(days);
        return (stats.workdays() + Math.min(stats.workingSaturdays(), 2)) * 8;
    }

    /**
     * Saturday-eligibility rows are tiers, not specific calendar dates: row 1 ("1 subota")
     * never qualifies for a bonus and is always inactive; row K>=2 requires
     * (workdays + K) * 8 hours and is only active while the month actually has at least K
     * working Saturdays — so if e.g. only 4 of a month's 5 Saturdays are worked, it's always
     * the trailing row (5) that goes inactive, regardless of which specific Saturday is off.
     */
    private void syncEligibilityActiveFlags(int year, int month, List<WorkCalendarDay> days) {
        long saturdayCount = days.stream()
                .filter(d -> d.getCalendarDate().getDayOfWeek() == DayOfWeek.SATURDAY)
                .count();

        if (saturdayCount == 0) {
            return;
        }

        bonusEligibilityRuleService.initializeForMonth(year, month);

        MonthWorkStats stats = computeMonthWorkStats(days);

        LocalDate period = YearMonth.of(year, month).atDay(1);
        List<BonusEligibilityRule> rules = bonusEligibilityRuleRepository
                .findByPeriodAndArchivedAtIsNullOrderByMinNumHoursAsc(period);

        for (BonusEligibilityRule rule : rules) {
            Integer ordinal = rule.getSaturdayCount();
            if (ordinal == null || ordinal < 1) {
                continue;
            }

            boolean active = ordinal != 1 && ordinal <= stats.workingSaturdays();
            int minHours = ordinal == 1 ? 0 : (stats.workdays() + ordinal) * 8;

            boolean changed = false;
            if (!Objects.equals(rule.getIsActive(), active)) {
                rule.setIsActive(active);
                changed = true;
            }
            if (!Objects.equals(rule.getMinNumHours(), minHours)) {
                rule.setMinNumHours(minHours);
                changed = true;
            }
            if (changed) {
                bonusEligibilityRuleRepository.save(rule);
            }
        }
    }
}
