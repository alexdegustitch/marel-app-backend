package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.bonus_calendar_sync.BonusCalendarSyncService;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRule;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleHistory;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleHistoryRepository;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleRepository;
import com.aleksandarparipovic.marel_app.bonus_min_hours_rules.BonusMinHoursRuleService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDay;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayType;
import com.aleksandarparipovic.marel_app.work_calendar_day.repository.WorkCalendarDayRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A hand-set minimum survives the work calendar, and the calendar's own answer survives beside
 * it.
 *
 * <p>WHY THIS EXISTS. `BonusCalendarSyncService` recomputes every month on every edit to any
 * day in it, and used to write that number straight over `min_num_hours`. A value typed in by
 * a person therefore lasted only until somebody marked one day a holiday — silently, with
 * nothing on screen to say it had happened. The two numbers are now separate columns, and the
 * pair of promises this test holds down is:
 *
 * <ul>
 *   <li>a calendar edit updates the SYSTEM value and leaves the manual one alone, so the
 *       effective minimum does not move;
 *   <li>a reset returns the month to the calendar's CURRENT answer, not to the one that stood
 *       when the override was made — which is the whole reason the calendar keeps working
 *       underneath an override rather than stopping.
 * </ul>
 */
@Transactional
class BonusMinHoursManualOverrideIT extends AbstractIntegrationTest {

    /** A month far enough out that no other fixture is seeding days into it. */
    private static final YearMonth MONTH = YearMonth.of(2031, 5);

    @Autowired private BonusCalendarSyncService syncService;
    @Autowired private BonusMinHoursRuleService ruleService;
    @Autowired private BonusMinHoursRuleRepository ruleRepository;
    @Autowired private BonusMinHoursRuleHistoryRepository historyRepository;
    @Autowired private WorkCalendarDayRepository workCalendarDayRepository;
    @Autowired private PayrollRunItemRepository payrollRunItemRepository;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("a calendar edit moves the system value and leaves the manual one applying")
    void manualValueSurvivesCalendarEdits() {
        seedCalendar(MONTH, null);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());

        BonusMinHoursRule rule = rule();
        int systemBefore = rule.getMinNumHours();
        assertThat(systemBefore).isPositive();
        // Nothing set by hand yet: the calendar's number is the one that applies.
        assertThat(rule.getManualMinNumHours()).isNull();
        assertThat(rule.getEffectiveMinNumHours()).isEqualTo(systemBefore);

        ruleService.setManual(rule.getId(), 100, "Dogovoreno sa proizvodnjom.");

        assertThat(rule().getEffectiveMinNumHours()).isEqualTo(100);
        assertThat(rule().getMinNumHours()).isEqualTo(systemBefore);

        // Somebody marks a workday a holiday — the edit that used to erase the override.
        makeFirstWorkdayAHoliday(MONTH);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());

        BonusMinHoursRule after = rule();
        assertThat(after.getMinNumHours())
                .as("the calendar's own answer moves")
                .isEqualTo(systemBefore - 8);
        assertThat(after.getManualMinNumHours())
                .as("the override is untouched")
                .isEqualTo(100);
        assertThat(after.getEffectiveMinNumHours())
                .as("and it is still what applies")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("a reset lands on the calendar's CURRENT answer, not the one it replaced")
    void resetReturnsToTheLatestSystemValue() {
        seedCalendar(MONTH, null);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());

        BonusMinHoursRule rule = rule();
        int systemAtOverrideTime = rule.getMinNumHours();

        ruleService.setManual(rule.getId(), 100, null);

        makeFirstWorkdayAHoliday(MONTH);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());

        ruleService.resetManual(rule.getId(), null);

        BonusMinHoursRule after = rule();
        assertThat(after.getManualMinNumHours()).isNull();
        assertThat(after.getEffectiveMinNumHours())
                .as("the calendar as it stands NOW")
                .isEqualTo(systemAtOverrideTime - 8)
                .isNotEqualTo(systemAtOverrideTime);
    }

    @Test
    @DisplayName("the history is a chain of closed intervals with exactly one open")
    void historyIsAChainOfIntervals() {
        seedCalendar(MONTH, null);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());
        BonusMinHoursRule rule = rule();

        ruleService.setManual(rule.getId(), 100, "Prvi dogovor.");
        makeFirstWorkdayAHoliday(MONTH);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());
        ruleService.resetManual(rule.getId(), null);

        List<BonusMinHoursRuleHistory> history =
                historyRepository.findByPeriodOrderByValidFromDesc(MONTH.atDay(1));

        // Four states: the calendar's first answer, the override, the calendar moving under
        // it, and the reset.
        assertThat(history).hasSize(4);
        assertThat(history).extracting(BonusMinHoursRuleHistory::getSource).containsExactly(
                BonusMinHoursRuleHistory.Source.MANUAL_RESET,
                BonusMinHoursRuleHistory.Source.CALENDAR_SYNC,
                BonusMinHoursRuleHistory.Source.MANUAL_SET,
                BonusMinHoursRuleHistory.Source.CALENDAR_SYNC);

        // Exactly one interval is open, and it is the newest.
        assertThat(history.stream().filter(h -> h.getValidUntil() == null)).hasSize(1);
        assertThat(history.getFirst().getValidUntil()).isNull();

        // Every closed interval ends where the next one begins: no gap, no overlap.
        for (int i = 0; i < history.size() - 1; i++) {
            assertThat(history.get(i + 1).getValidUntil())
                    .isEqualTo(history.get(i).getValidFrom());
        }

        // A row explains itself: while the override stood, both numbers are on it.
        BonusMinHoursRuleHistory whileOverridden = history.get(1);
        assertThat(whileOverridden.getManualMinNumHours()).isEqualTo(100);
        assertThat(whileOverridden.getSystemMinNumHours()).isPositive();
        assertThat(whileOverridden.getEffectiveMinNumHours()).isEqualTo(100);
    }

    @Test
    @DisplayName("a calendar edit that changes nothing writes no history")
    void unchangedSyncsAreNotRecorded() {
        seedCalendar(MONTH, null);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());

        // The calendar recomputes a month on every edit to any day in it and almost always
        // arrives at the number already there. Recording those would bury the real changes.
        assertThat(historyRepository.findByPeriodOrderByValidFromDesc(MONTH.atDay(1))).hasSize(1);
    }

    @Test
    @DisplayName("a locked month refuses a manual change rather than quietly ignoring it")
    void aLockedMonthCannotBeChanged() {
        seedCalendar(MONTH, null);
        syncService.syncMonth(MONTH.getYear(), MONTH.getMonthValue());
        BonusMinHoursRule rule = rule();

        // A signed-off month. Changing the rule under it would leave the signature attached to
        // arithmetic nobody approved.
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(MONTH).build();
        PayrollRunItem item = scenario.item();
        item.setStatus("LOCKED");
        payrollRunItemRepository.saveAndFlush(item);

        assertThatThrownBy(() -> ruleService.setManual(rule.getId(), 100, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zaključan");

        assertThatThrownBy(() -> ruleService.resetManual(rule.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zaključan");

        // Refused, not half-applied.
        assertThat(rule().getManualMinNumHours()).isNull();
    }

    private BonusMinHoursRule rule() {
        ruleRepository.flush();
        return ruleRepository.findByPeriodAndArchivedAtIsNull(MONTH.atDay(1)).orElseThrow();
    }

    /** Weekdays are workdays, weekends are not — the shape the year auto-fill produces. */
    private void seedCalendar(YearMonth month, Boolean unused) {
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            DayOfWeek dow = date.getDayOfWeek();
            workCalendarDayRepository.save(WorkCalendarDay.builder()
                    .calendarDate(date)
                    .dayType(dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
                            ? WorkCalendarDayType.NON_WORKING
                            : WorkCalendarDayType.WORKDAY)
                    .build());
        }
        workCalendarDayRepository.flush();
    }

    /** Takes exactly one working day out of the month — eight hours off the system value. */
    private void makeFirstWorkdayAHoliday(YearMonth month) {
        LocalDate date = month.atDay(1);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        WorkCalendarDay day = workCalendarDayRepository
                .findByCalendarDateBetweenOrderByCalendarDateAsc(date, date).getFirst();
        day.setDayType(WorkCalendarDayType.HOLIDAY);
        workCalendarDayRepository.saveAndFlush(day);
    }
}
