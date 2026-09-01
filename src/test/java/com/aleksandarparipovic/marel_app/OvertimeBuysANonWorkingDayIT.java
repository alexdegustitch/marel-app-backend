package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceCompensationAllocator;
import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceCompensationRepository;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceOutcome;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecord;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceCategoryCodes;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceLogWriter;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecordRepository;
import com.aleksandarparipovic.marel_app.absence_record.ShiftAbsenceSync;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategory;
import com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategoryRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecord;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecordRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole chain, against a real database: hours worked over eight become a
 * bank, the bank buys a full day off, and the day it buys stops spoiling the
 * weekend bonus.
 *
 * <p>{@code @Transactional}, for the reason ProbationRecalcIT already gives: the
 * recalculation's {@code TransactionTemplate} JOINS the caller's transaction
 * rather than opening its own, so the whole chain is visible here and none of it
 * is committed. Without it this class committed eleven scenarios into the
 * container the whole suite shares, and the adjustment catalogue they left
 * behind made the rule matrix incomplete for every scheme a later test tried to
 * activate — nineteen tests in three other classes, none of them about absence.
 */
@Transactional
class OvertimeBuysANonWorkingDayIT extends AbstractIntegrationTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 8);
    /** A Wednesday, so the weekend of the same week is a Saturday two days later. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 19);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);
    private static final int FULL_SHIFT = 480;

    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private WorkCodeCategoryRepository categoryRepository;
    @Autowired private OperationRepository operationRepository;
    @Autowired private OvertimeRecordRepository overtimeRepository;
    @Autowired private AbsenceRecordRepository absenceRepository;
    @Autowired private AbsenceCompensationRepository compensationRepository;
    @Autowired private AbsenceCompensationAllocator allocator;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private WorkLogRepository workLogRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ShiftAbsenceSync shiftAbsenceSync;
    @Autowired private AbsenceLogWriter absenceLogWriter;
    @Autowired private DailyReportCategoryRepository categoryRowRepository;
    @Autowired private PayrollRunItemRepository payrollRunItemRepository;

    private Employee employee;
    private PayrollRunItem payrollItem;
    private WorkCodeCategory workCategory;
    private WorkCodeCategory unpaidAbsence;
    private Operation workOperation;

    @BeforeEach
    void setUp() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(MONTH).build();
        employee = scenario.employee();
        payrollItem = scenario.item();
        workCategory = scenario.workCategory();
        workOperation = fixture.operation(workCategory, 10);

        unpaidAbsence = absenceCategory("NO", "Neplaćeno odsustvo");
        WorkCodeCategory nonWorkingDay = absenceCategory("ND", "Neradni dan");
        ndOperation(nonWorkingDay);
    }

    // ── The bank ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ten hours worked in a day leave two hours of overtime")
    void overtimeIsWhatTheDayRanOver() {
        workedShift(WEDNESDAY, 6, 600);

        assertThat(overtimeFor(WEDNESDAY))
                .get()
                .extracting(OvertimeRecord::getOvertimeMinutes)
                .isEqualTo(120);
    }

    @Test
    @DisplayName("a regular eight-hour day leaves no row at all")
    void aRegularDayHasNoOvertimeRow() {
        workedShift(WEDNESDAY, 6, FULL_SHIFT);

        assertThat(overtimeFor(WEDNESDAY)).isEmpty();
    }

    /**
     * Eight hours in the first shift and eight in the third is eight hours of
     * overtime, though neither shift on its own is longer than a regular one.
     * This is the whole reason the row is keyed by day.
     */
    @Test
    @DisplayName("two full shifts in one day are eight hours of overtime, not none")
    void overtimeIsCountedOverTheDayNotTheShift() {
        workedShift(WEDNESDAY, 6, FULL_SHIFT);
        workedShift(WEDNESDAY, 22, FULL_SHIFT);

        assertThat(overtimeFor(WEDNESDAY))
                .get()
                .extracting(OvertimeRecord::getOvertimeMinutes)
                .isEqualTo(FULL_SHIFT);
    }

    @Test
    @DisplayName("shortening the day back to eight hours removes the row")
    void theRowGoesWhenTheOvertimeDoes() {
        WorkShift shift = workedShift(WEDNESDAY, 6, 600);
        assertThat(overtimeFor(WEDNESDAY)).isPresent();

        changeWorkTo(shift, FULL_SHIFT);

        assertThat(overtimeFor(WEDNESDAY)).isEmpty();
    }

    // ── NO or ND ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a full day off the bank cannot cover whole stays NO, and spends the bank anyway")
    void anUncoverableDayStaysUnpaidAbsence() {
        workedShift(WEDNESDAY, 6, 600);              // 120 in the bank
        AbsenceRecord absence = fullDayAbsence(THURSDAY);

        allocator.allocate(employee.getId(), MONTH);

        AbsenceRecord after = reload(absence);
        assertThat(after.getOutcome()).isEqualTo(AbsenceOutcome.NO);
        assertThat(after.getNdWorkLog()).isNull();

        // Two hours against a full shift buy no neradni dan, and are spent all
        // the same: the order absences happened decides where the bank goes, not
        // what the hours could have bought later. Only 360 of the 480 are left
        // uncovered, and only those reach the payroll.
        assertThat(after.getCompensatedMinutes()).isEqualTo(120);
        assertThat(compensationRepository.findForAbsences(List.of(absence.getId()))).hasSize(1);
    }

    @Test
    @DisplayName("a bank that covers the whole shift makes the day a neradni dan")
    void aCoveredDayBecomesNonWorking() {
        workedShift(WEDNESDAY, 6, 960);              // 480 in the bank
        AbsenceRecord absence = fullDayAbsence(THURSDAY);

        allocator.allocate(employee.getId(), MONTH);

        AbsenceRecord after = reload(absence);
        assertThat(after.getOutcome()).isEqualTo(AbsenceOutcome.ND);
        assertThat(after.getCompensatedMinutes()).isEqualTo(FULL_SHIFT);
        assertThat(after.getNdWorkLog()).isNotNull();

        assertThat(compensationRepository.findForAbsences(List.of(absence.getId())))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getCompensatedMinutes()).isEqualTo(FULL_SHIFT);
                    assertThat(c.getOvertimeRecord().getWorkDate()).isEqualTo(WEDNESDAY);
                });
    }

    /**
     * The supervisor adds two hours to an earlier shift. Nothing about the
     * absence changed, but the bank now covers the whole day.
     */
    @Test
    @DisplayName("overtime added later turns a refused day into a neradni dan")
    void aBiggerBankBuysTheDayAfterAll() {
        WorkShift wednesday = workedShift(WEDNESDAY, 6, 600);
        AbsenceRecord absence = fullDayAbsence(THURSDAY);
        allocator.allocate(employee.getId(), MONTH);
        assertThat(reload(absence).getOutcome()).isEqualTo(AbsenceOutcome.NO);

        changeWorkTo(wednesday, 960);

        allocator.allocate(employee.getId(), MONTH);
        assertThat(reload(absence).getOutcome()).isEqualTo(AbsenceOutcome.ND);
    }

    @Test
    @DisplayName("and shrinking the bank takes the neradni dan back, log and all")
    void aSmallerBankRevokesIt() {
        WorkShift wednesday = workedShift(WEDNESDAY, 6, 960);
        AbsenceRecord absence = fullDayAbsence(THURSDAY);
        allocator.allocate(employee.getId(), MONTH);

        Long ndLogId = reload(absence).getNdWorkLog().getId();
        assertThat(workLogRepository.findById(ndLogId)).isPresent();

        changeWorkTo(wednesday, FULL_SHIFT);

        allocator.allocate(employee.getId(), MONTH);

        AbsenceRecord after = reload(absence);
        assertThat(after.getOutcome()).isEqualTo(AbsenceOutcome.NO);
        assertThat(after.getNdWorkLog()).isNull();
        assertThat(workLogRepository.findById(ndLogId)).isEmpty();
    }

    // ── What the ND log must NOT do ──────────────────────────────────────────

    /**
     * The regression this feature was one line away from shipping: the ND log
     * covers a whole shift, so counted as time present it would put 480 minutes
     * into the denominator of totalWeightedNormMinutes / totalShiftMinutes and
     * pull a 100 % month down to about 95 % for a day the employee was excused
     * from.
     */
    @Test
    @DisplayName("a neradni dan adds no shift minutes, so it cannot drag the month's efficiency")
    void theNdLogIsNotCountedAsTimePresent() {
        workedShift(WEDNESDAY, 6, 960);
        WorkShift thursday = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
        absence(thursday);
        allocator.allocate(employee.getId(), MONTH);

        fixture.recalculate(thursday);

        DailyReport report = dailyReportRepository.findByWorkShiftId(thursday.getId()).orElseThrow();
        assertThat(report.getTotalShiftMinutes()).isZero();
        assertThat(report.getTotalWorkMinutes()).isZero();
        // And nothing is owed either: the bank bought the whole day back, so
        // there is no uncovered absence left for the payroll to charge. The
        // hours are not lost — they were paid on the day they were worked.
        assertThat(report.getTotalAbsenceUnpaidMinutes()).isZero();
        assertThat(report.getTotalCompensatedMinutes()).isEqualTo(FULL_SHIFT);
    }

    /**
     * If the ND log were measured as work, it would report overtime of its own
     * and refill the very bank that paid for it.
     */
    @Test
    @DisplayName("a neradni dan on a ten-hour shift creates no overtime of its own")
    void theNdLogDoesNotRefillTheBank() {
        workedShift(WEDNESDAY, 6, 1080);             // 600 in the bank
        WorkShift thursday = fixture.workShift(employee, THURSDAY, 6, 600);
        absence(thursday);
        allocator.allocate(employee.getId(), MONTH);
        fixture.recalculate(thursday);

        assertThat(overtimeFor(THURSDAY)).isEmpty();
    }

    // ── The weekend bonus ────────────────────────────────────────────────────

    /**
     * ND changes what a day is PAID as, not whether anybody turned up. The
     * weekend bonus is earned by being there every day of the week, and a day
     * bought back with earlier overtime is still a day nobody was there — so it
     * spoils the week exactly as an unpaid absence does. The two differ in the
     * payroll and nowhere else.
     */
    @Test
    @DisplayName("an ND day spoils the weekend bonus just as NO does — being bought back is not being there")
    void ndStillSpoilsTheWeek() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        LocalDate saturday = LocalDate.of(2026, 8, 22);

        // Monday and Tuesday worked well past the 180-minute threshold.
        workedShift(monday, 6, FULL_SHIFT);
        workedShift(monday.plusDays(1), 6, FULL_SHIFT);
        // Wednesday: long enough to buy Thursday off.
        workedShift(WEDNESDAY, 6, 960);
        // Friday worked, so only Thursday is in question.
        workedShift(LocalDate.of(2026, 8, 21), 6, FULL_SHIFT);

        WorkShift thursday = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
        AbsenceRecord absence = absence(thursday);

        // Before the bank is allocated, Thursday is a plain unpaid absence.
        assertThat(missingDaysBefore(monday, saturday)).isEqualTo(1);

        allocator.allocate(employee.getId(), MONTH);
        assertThat(reload(absence).getOutcome()).isEqualTo(AbsenceOutcome.ND);

        // Still one. The day became a neradni dan and the week is still spoiled:
        // no work happened on Thursday, so it has no bonus-eligible minutes, and
        // the query never has to ask WHY a day fell short.
        assertThat(missingDaysBefore(monday, saturday)).isEqualTo(1);
    }

    /**
     * August 2026 opens on a Saturday, which is the case this is about: the week
     * it belongs to starts on 27 July. Asked across that boundary, the bonus for
     * one month's weekend would be decided by another month's attendance.
     */
    @Test
    @DisplayName("a Saturday on the 1st has nothing before it in the month, and earns the bonus by default")
    void theFirstOfTheMonthOnASaturdayHasAnEmptyWindow() {
        LocalDate saturdayFirst = LocalDate.of(2026, 8, 1);
        assertThat(saturdayFirst.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);

        // Nothing worked in the last week of July, which under the old window
        // would have been five days short and cost the bonus.
        assertThat(missingDaysBefore(saturdayFirst.withDayOfMonth(1), saturdayFirst)).isZero();
    }

    @Test
    @DisplayName("and where the 1st is a working day, only the days inside the month are asked about")
    void theWindowStartsAtTheFirstOfTheMonth() {
        LocalDate saturday = LocalDate.of(2026, 8, 1);
        LocalDate sunday = LocalDate.of(2026, 8, 2);

        // Saturday the 1st worked past the threshold; Sunday's window is that day
        // alone, and July is not in it.
        workedShift(saturday, 6, FULL_SHIFT);
        assertThat(missingDaysBefore(sunday.withDayOfMonth(1), sunday)).isZero();
    }

    // ── The NO log and the absence record it mirrors ─────────────────────────

    @Nested
    @DisplayName("a whole shift entered as a NO operation")
    class TheUnpaidAbsenceLog {

        @Test
        @DisplayName("writes the absence record behind it, so the bank can see the day at all")
        void mirrorsIntoAnAbsenceRecord() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            absenceLogWriter.ensureUnpaidAbsenceLog(shift);

            shiftAbsenceSync.syncForShift(shift);

            assertThat(absenceRepository.findActiveForShift(shift.getId()))
                    .singleElement()
                    .satisfies(a -> {
                        assertThat(a.getAbsenceMinutes()).isEqualTo(FULL_SHIFT);
                        assertThat(a.getWorkCodeCategory().getCategoryNo())
                                .isEqualTo(AbsenceCategoryCodes.UNPAID_ABSENCE);
                    });
        }

        @Test
        @DisplayName("counts its minutes ONCE — the log and the record are one fact, not two")
        void doesNotCountTheDayTwice() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            absenceLogWriter.ensureUnpaidAbsenceLog(shift);
            shiftAbsenceSync.syncForShift(shift);

            fixture.recalculate(shift);

            DailyReport report = dailyReportRepository.findByWorkShiftId(shift.getId()).orElseThrow();
            assertThat(report.getTotalAbsenceUnpaidMinutes()).isEqualTo(FULL_SHIFT);
            assertThat(report.getTotalShiftMinutes()).isZero();
        }

        @Test
        @DisplayName("removing the log withdraws the absence with it")
        void removingTheLogWithdrawsTheAbsence() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            WorkLog noLog = absenceLogWriter.ensureUnpaidAbsenceLog(shift);
            shiftAbsenceSync.syncForShift(shift);
            assertThat(absenceRepository.findActiveForShift(shift.getId())).hasSize(1);

            workLogRepository.delete(noLog);
            entityManager.flush();
            shiftAbsenceSync.syncForShift(shift);

            assertThat(absenceRepository.findActiveForShift(shift.getId())).isEmpty();
        }

        @Test
        @DisplayName("is refused over part of a shift — that is a gap, and gaps go through the dialog")
        void refusesAPartialUnpaidLog() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            fixture.workLog(shift, unpaidOperation(), unpaidAbsence, 360, 120, 0);

            assertThatThrownBy(() -> shiftAbsenceSync.syncForShift(shift))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("celu smenu");
        }

        @Test
        @DisplayName("is refused beside recorded work — a day off is not half a day")
        void refusesUnpaidLogBesideWork() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            fixture.workLog(shift, workOperation, workCategory, 0, 240, 50);
            absenceLogWriter.ensureUnpaidAbsenceLog(shift);

            assertThatThrownBy(() -> shiftAbsenceSync.syncForShift(shift))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("uz uneti rad");
        }
    }

    @Nested
    @DisplayName("the log swaps when the bank decides")
    class TheLogSwaps {

        @Test
        @DisplayName("NO becomes ND, and the two never stand on the shift together")
        void theNoLogBecomesAndLog() {
            workedShift(WEDNESDAY, 6, 960);
            WorkShift thursday = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            absenceLogWriter.ensureUnpaidAbsenceLog(thursday);
            shiftAbsenceSync.syncForShift(thursday);

            allocator.allocate(employee.getId(), MONTH);

            assertThat(absenceLogWriter.findLog(thursday, AbsenceCategoryCodes.UNPAID_ABSENCE)).isEmpty();
            assertThat(absenceLogWriter.findLog(thursday, AbsenceCategoryCodes.NON_WORKING_DAY)).isPresent();
        }

        @Test
        @DisplayName("and comes back as NO when the bank no longer covers it")
        void andSwapsBackWhenTheBankShrinks() {
            WorkShift wednesday = workedShift(WEDNESDAY, 6, 960);
            WorkShift thursday = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            absenceLogWriter.ensureUnpaidAbsenceLog(thursday);
            shiftAbsenceSync.syncForShift(thursday);
            allocator.allocate(employee.getId(), MONTH);

            changeWorkTo(wednesday, FULL_SHIFT);
            allocator.allocate(employee.getId(), MONTH);

            assertThat(absenceLogWriter.findLog(thursday, AbsenceCategoryCodes.NON_WORKING_DAY)).isEmpty();
            assertThat(absenceLogWriter.findLog(thursday, AbsenceCategoryCodes.UNPAID_ABSENCE)).isPresent();
        }
    }

    @Nested
    @DisplayName("what the payroll is told")
    class WhatThePayrollIsTold {

        /**
         * The reason absences have to become category rows at all:
         * daily_report_categories -> monthly_report_categories ->
         * payroll_run_item_categories is the only path a category takes to a
         * payslip. Without a row, two hours missed showed up as a smaller total
         * with no line saying why.
         */
        @Test
        @DisplayName("two hours missed become a NO line on the day, not just a smaller total")
        void anAbsenceBecomesACategoryRow() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            fixture.workLog(shift, workOperation, workCategory, 0, 360, 100);
            partialAbsence(shift, 360, 120);

            fixture.recalculate(shift);

            DailyReport report = dailyReportRepository.findByWorkShiftId(shift.getId()).orElseThrow();
            assertThat(categoryRowRepository.findAllByDailyReportIds(List.of(report.getId())))
                    .filteredOn(c -> AbsenceCategoryCodes.UNPAID_ABSENCE
                            .equals(c.getWorkCodeCategory().getCategoryNo()))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.getTotalMinutes()).isEqualTo(120);
                        // Unpaid is unpaid; being covered by the bank never
                        // changes that.
                        assertThat(row.getTotalPaidMinutes()).isZero();
                        assertThat(row.getSourceType()).isEqualTo("ABSENCE");
                    });
        }

        /**
         * Three hours missed with two bought back is one hour of NO. The other
         * two were already worked, and paid for on the day they were worked;
         * charging them again here would take the same hours off twice.
         */
        @Test
        @DisplayName("only the part the bank did not cover reaches the line")
        void onlyTheUncoveredPartIsPriced() {
            workedShift(WEDNESDAY, 6, 600);                  // 120 in the bank
            WorkShift thursday = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            fixture.workLog(thursday, workOperation, workCategory, 0, 300, 80);
            partialAbsence(thursday, 300, 180);              // away 3h

            allocator.allocate(employee.getId(), MONTH);
            entityManager.flush();
            entityManager.clear();
            fixture.recalculate(thursday);

            DailyReport report = dailyReportRepository.findByWorkShiftId(thursday.getId()).orElseThrow();
            assertThat(categoryRowRepository.findAllByDailyReportIds(List.of(report.getId())))
                    .filteredOn(c -> AbsenceCategoryCodes.UNPAID_ABSENCE
                            .equals(c.getWorkCodeCategory().getCategoryNo()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.getTotalMinutes()).isEqualTo(60));
        }

        /**
         * A neradni dan was bought back whole, so there is nothing left of it for
         * the payroll to charge. No row at all, rather than a row of zero.
         */
        @Test
        @DisplayName("a fully covered day leaves no line behind")
        void aFullyCoveredDayHasNoLine() {
            workedShift(WEDNESDAY, 6, 960);                  // 480 in the bank
            WorkShift thursday = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            absence(thursday);

            allocator.allocate(employee.getId(), MONTH);
            entityManager.flush();
            entityManager.clear();
            fixture.recalculate(thursday);

            DailyReport report = dailyReportRepository.findByWorkShiftId(thursday.getId()).orElseThrow();
            assertThat(categoryRowRepository.findAllByDailyReportIds(List.of(report.getId())))
                    .filteredOn(c -> AbsenceCategoryCodes.UNPAID_ABSENCE
                            .equals(c.getWorkCodeCategory().getCategoryNo()))
                    .isEmpty();
        }

        /**
         * The row must not be measured as work. Left in the denominator, the
         * day's rate would fall in proportion to how long somebody was away —
         * which measures their absence rather than their work.
         */
        @Test
        @DisplayName("and recording it does not move the day's efficiency")
        void anAbsenceDoesNotDragTheRate() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            fixture.workLog(shift, workOperation, workCategory, 0, 360, 100);
            fixture.recalculate(shift);

            DailyReport before = dailyReportRepository.findByWorkShiftId(shift.getId()).orElseThrow();
            BigDecimal rateBefore = before.getApprovedPerformanceRate();
            assertThat(rateBefore).isNotNull().isNotEqualTo(BigDecimal.ZERO);

            partialAbsence(shift, 360, 120);
            entityManager.flush();
            entityManager.clear();
            fixture.recalculate(shift);

            DailyReport after = dailyReportRepository.findByWorkShiftId(shift.getId()).orElseThrow();
            assertThat(after.getApprovedPerformanceRate()).isEqualByComparingTo(rateBefore);
            assertThat(after.getTotalAbsenceUnpaidMinutes()).isEqualTo(120);
        }

        @Test
        @DisplayName("recording an absence flags the payroll to reprice itself")
        void recordingAnAbsenceFlagsThePayroll() {
            assertThat(payrollItem.getNeedsRecalculation()).isFalse();

            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            absenceLogWriter.ensureUnpaidAbsenceLog(shift);
            shiftAbsenceSync.syncForShift(shift);

            // A bulk UPDATE does not reach the instance already in the session.
            entityManager.flush();
            entityManager.clear();
            assertThat(payrollRunItemRepository.findById(payrollItem.getId()).orElseThrow()
                    .getNeedsRecalculation()).isTrue();
        }

        @Test
        @DisplayName("and so does withdrawing one")
        void withdrawingFlagsItToo() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            WorkLog noLog = absenceLogWriter.ensureUnpaidAbsenceLog(shift);
            shiftAbsenceSync.syncForShift(shift);
            payrollRunItemRepository.findById(payrollItem.getId())
                    .ifPresent(i -> i.setNeedsRecalculation(false));
            entityManager.flush();

            workLogRepository.delete(noLog);
            entityManager.flush();
            shiftAbsenceSync.syncForShift(shift);

            entityManager.flush();
            entityManager.clear();
            assertThat(payrollRunItemRepository.findById(payrollItem.getId()).orElseThrow()
                    .getNeedsRecalculation()).isTrue();
        }
    }

    @Nested
    @DisplayName("work recorded over an absence")
    class WorkOverAnAbsence {

        @Test
        @DisplayName("withdraws it — entering work there is a correction, not a conflict")
        void withdrawsTheAbsence() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            partialAbsence(shift, 360, 120);          // away 12:00–14:00
            assertThat(absenceRepository.findActiveForShift(shift.getId())).hasSize(1);

            fixture.workLog(shift, workOperation, workCategory, 360, 120, 40);
            shiftAbsenceSync.syncForShift(shift);

            assertThat(absenceRepository.findActiveForShift(shift.getId())).isEmpty();
        }

        @Test
        @DisplayName("takes its compensations with it, so no overtime day still claims to have paid for it")
        void takesTheCompensationsWithIt() {
            workedShift(WEDNESDAY, 6, 600);           // 120 in the bank
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            AbsenceRecord absence = partialAbsence(shift, 360, 120);

            allocator.allocate(employee.getId(), MONTH);
            assertThat(compensationRepository.findForAbsences(List.of(absence.getId()))).isNotEmpty();

            fixture.workLog(shift, workOperation, workCategory, 360, 120, 40);
            shiftAbsenceSync.syncForShift(shift);

            assertThat(compensationRepository.findForAbsences(List.of(absence.getId()))).isEmpty();
        }

        @Test
        @DisplayName("leaves an absence alone when the work does not reach it")
        void leavesUntouchedAbsencesAlone() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            partialAbsence(shift, 360, 120);          // away 12:00–14:00

            fixture.workLog(shift, workOperation, workCategory, 0, 240, 80);  // worked 06:00–10:00
            shiftAbsenceSync.syncForShift(shift);

            assertThat(absenceRepository.findActiveForShift(shift.getId())).hasSize(1);
        }

        @Test
        @DisplayName("a NO log is not work, and never withdraws the absence it mirrors")
        void theNoLogDoesNotWithdrawItsOwnAbsence() {
            WorkShift shift = fixture.workShift(employee, THURSDAY, 6, FULL_SHIFT);
            absenceLogWriter.ensureUnpaidAbsenceLog(shift);

            shiftAbsenceSync.syncForShift(shift);
            shiftAbsenceSync.syncForShift(shift);      // idempotent: still there

            assertThat(absenceRepository.findActiveForShift(shift.getId())).hasSize(1);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Away for part of the shift, as the absence dialog records it. */
    private AbsenceRecord partialAbsence(WorkShift shift, int minutesIn, int minutes) {
        return absenceRepository.saveAndFlush(AbsenceRecord.builder()
                .employee(employee)
                .workShift(shift)
                .workCodeCategory(unpaidAbsence)
                .startAt(shift.getStartAt().plusMinutes(minutesIn))
                .endAt(shift.getStartAt().plusMinutes(minutesIn + minutes))
                .absenceMinutes(minutes)
                .normMultiplierSnapshot(BigDecimal.ZERO)
                .paidMinutes(0)
                .compensatedMinutes(0)
                .isActive(true)
                .build());
    }


    /** The seeded operation NO logs hang from; work_logs.operation_id is NOT NULL. */
    private Operation unpaidOperation() {
        return operationRepository
                .findActiveByWorkCodeCategoryNo(AbsenceCategoryCodes.UNPAID_ABSENCE)
                .get(0);
    }


    private int missingDaysBefore(LocalDate weekStart, LocalDate day) {
        return dailyReportRepository.countPreviousDaysWithInsufficientBonusMinutes(
                employee.getId(), weekStart, day, 180);
    }

    /**
     * Change a shift's recorded work, then rebuild the day.
     *
     * <p>The clear() is what makes a SECOND recalculation in one test behave the
     * way production does. The recalc queue is driven by native SQL, so the
     * version it bumps never reaches the DailyRecalcQueue instance this
     * transaction already cached — and processJob's stale-version guard then
     * reschedules the job instead of writing anything. In production each pass
     * gets a fresh session and never sees that; here the session is the test's,
     * and it has to be emptied to match.
     */
    private void changeWorkTo(WorkShift shift, int minutes) {
        WorkLog log = workLogRepository.findActiveLogsWithRefsForShift(shift.getId()).get(0);
        log.setEndAt(shift.getStartAt().plusMinutes(minutes));
        workLogRepository.saveAndFlush(log);

        entityManager.flush();
        entityManager.clear();

        fixture.recalculate(shift);
    }

    private WorkShift workedShift(LocalDate date, int startHour, int minutes) {
        WorkShift shift = fixture.workShift(employee, date, startHour, minutes);
        fixture.workLog(shift, workOperation, workCategory, 0, minutes, 100);
        fixture.recalculate(shift);
        return shift;
    }

    private AbsenceRecord fullDayAbsence(LocalDate date) {
        return absence(fixture.workShift(employee, date, 6, FULL_SHIFT));
    }

    private AbsenceRecord absence(WorkShift shift) {
        return absenceRepository.saveAndFlush(AbsenceRecord.builder()
                .employee(employee)
                .workShift(shift)
                .workCodeCategory(unpaidAbsence)
                .startAt(shift.getStartAt())
                .endAt(shift.getEndAt())
                // Computed, not read from shift.getTotalMinutes(): that is a
                // generated column mapped insertable/updatable = false and WITHOUT
                // @Generated, so it is null on the instance that was just saved.
                .absenceMinutes((int) java.time.Duration
                        .between(shift.getStartAt(), shift.getEndAt()).toMinutes())
                .normMultiplierSnapshot(BigDecimal.ZERO)
                .paidMinutes(0)
                .compensatedMinutes(0)
                .isActive(true)
                .build());
    }

    private AbsenceRecord reload(AbsenceRecord absence) {
        return absenceRepository.findById(absence.getId()).orElseThrow();
    }

    private java.util.Optional<OvertimeRecord> overtimeFor(LocalDate date) {
        return overtimeRepository.findByEmployee_IdAndWorkDate(employee.getId(), date);
    }

    /**
     * Find or create rather than create: the rollback normally clears these
     * between tests, but the seeded ND and NO from V25 are already there and
     * {@code ex_work_code_categories_no_overlap} refuses a second one whose
     * validity overlaps.
     */
    private WorkCodeCategory absenceCategory(String categoryNo, String name) {
        return categoryRepository
                .findInForceByCategoryNo(categoryNo, WEDNESDAY)
                .orElseGet(() -> newAbsenceCategory(categoryNo, name));
    }

    private WorkCodeCategory newAbsenceCategory(String categoryNo, String name) {
        return categoryRepository.saveAndFlush(WorkCodeCategory.builder()
                .categoryNo(categoryNo)
                .categoryName(name)
                .type("ABSENCE")
                .isPaid(false)
                .normMultiplier(0d)
                .isActive(true)
                .fixedHourlyRate(false)
                .affectsMealAllowance(false)
                .allowsParallelWork(false)
                .displayOrder(90)
                .baseCategory(false)
                .build());
    }

    /**
     * The one operation ND hangs from; work_logs.operation_id is NOT NULL.
     *
     * <p>Created once for the class, for the same reason the categories are:
     * NonWorkingDayWriter refuses to guess between two of them.
     */
    private void ndOperation(WorkCodeCategory nonWorkingDay) {
        if (!operationRepository.findActiveByWorkCodeCategoryNo("ND").isEmpty()) {
            return;
        }
        Operation operation = new Operation();
        operation.setProduct(fixture.product("Neradni dan"));
        operation.setWorkCodeCategory(nonWorkingDay);
        operation.setOpName("Neradni dan");
        operation.setNormRequired(false);
        operation.setActive(true);
        operationRepository.saveAndFlush(operation);
    }
}
