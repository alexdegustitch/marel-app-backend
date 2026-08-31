package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceCompensationAllocator;
import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceCompensationRepository;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceOutcome;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecord;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecordRepository;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole chain, against a real database: hours worked over eight become a
 * bank, the bank buys a full day off, and the day it buys stops spoiling the
 * weekend bonus.
 *
 * <p>Not {@code @Transactional}: the recalculation commits in its own
 * transaction, and what this test is about is what ends up committed.
 *
 * <p>The ND and NO categories, and the single operation that carries ND, are
 * created here. They exist in the production database but not in any migration,
 * so a test database has neither — which is itself worth knowing.
 */
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

    private Employee employee;
    private WorkCodeCategory workCategory;
    private WorkCodeCategory unpaidAbsence;
    private Operation workOperation;

    @BeforeEach
    void setUp() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().period(MONTH).build();
        employee = scenario.employee();
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

        WorkLog log = workLogRepository.findActiveLogsWithRefsForShift(shift.getId()).get(0);
        log.setEndAt(shift.getStartAt().plusMinutes(FULL_SHIFT));
        workLogRepository.saveAndFlush(log);
        fixture.recalculate(shift);

        assertThat(overtimeFor(WEDNESDAY)).isEmpty();
    }

    // ── NO or ND ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a full day off the bank cannot cover whole stays NO, and takes nothing")
    void anUncoverableDayStaysUnpaidAbsence() {
        workedShift(WEDNESDAY, 6, 600);              // 120 in the bank
        AbsenceRecord absence = fullDayAbsence(THURSDAY);

        allocator.allocate(employee.getId(), MONTH);

        AbsenceRecord after = reload(absence);
        assertThat(after.getOutcome()).isEqualTo(AbsenceOutcome.NO);
        assertThat(after.getCompensatedMinutes()).isZero();
        assertThat(after.getNdWorkLog()).isNull();
        // All or nothing: the 120 minutes are still there for a day that can use them.
        assertThat(compensationRepository.findForAbsences(List.of(absence.getId()))).isEmpty();
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

        WorkLog log = workLogRepository.findActiveLogsWithRefsForShift(wednesday.getId()).get(0);
        log.setEndAt(wednesday.getStartAt().plusMinutes(960));
        workLogRepository.saveAndFlush(log);
        fixture.recalculate(wednesday);

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

        WorkLog log = workLogRepository.findActiveLogsWithRefsForShift(wednesday.getId()).get(0);
        log.setEndAt(wednesday.getStartAt().plusMinutes(FULL_SHIFT));
        workLogRepository.saveAndFlush(log);
        fixture.recalculate(wednesday);

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
        // The minutes are not lost: they are absence, through the record itself.
        assertThat(report.getTotalAbsenceUnpaidMinutes()).isEqualTo(FULL_SHIFT);
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

    @Test
    @DisplayName("an ND day does not count as a day that spoils the weekend bonus; NO does")
    void ndIsExcusedFromTheWeeklyCheck() {
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

        assertThat(missingDaysBefore(monday, saturday)).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int missingDaysBefore(LocalDate weekStart, LocalDate day) {
        return dailyReportRepository.countPreviousDaysWithInsufficientBonusMinutes(
                employee.getId(), weekStart, day, 180);
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
     * Find or create: this class is not {@code @Transactional}, so setUp runs
     * again against the rows the previous test committed, and
     * {@code ex_work_code_categories_no_overlap} refuses a second 'NO' whose
     * validity overlaps the first.
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
