package com.aleksandarparipovic.marel_app.employee_leave;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceLogWriter;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecord;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecordRepository;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee.repository.EmployeeRepository;
import com.aleksandarparipovic.marel_app.employee_leave.dto.EmployeeLeaveDtos.*;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItem;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.report_worker.DailyRecalcRequestedEvent;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDay;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayEffectiveStatus;
import com.aleksandarparipovic.marel_app.work_calendar_day.WorkCalendarDayType;
import com.aleksandarparipovic.marel_app.work_calendar_day.repository.WorkCalendarDayRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.WorkShiftService;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A leave entered for a whole od–do period: sick leave, vacation, any
 * absence-type category.
 *
 * <p>THE PLAN IS COMPUTED ONCE and both doors go through it — the preview shows
 * it and the apply executes it — so what the user was shown and what is written
 * cannot disagree. For every day of the period the plan decides one of:
 * create a shift, skip it (weekend, holiday, already the same type), name the
 * conflicting shifts on it, or state that the month's payroll is already
 * handed over.
 *
 * <p><b>What a created day IS.</b> A work shift carrying the chosen category
 * (the karton shows it like any other day) plus an absence record spanning the
 * whole shift — the record is what the daily recalculation prices, exactly the
 * shape a whole-day NO already has. The category's own norm multiplier does the
 * paying: B at 0.6, GO at 1, NO at 0. No work logs — a leave day holds no work.
 *
 * <p><b>The thirty-day rule.</b> After thirty continuous CALENDAR days of
 * STANDARD sick leave, every further day is written as the EXTENDED category
 * (B30). Weekends, holidays and more of the same sick leave do not break the
 * run; a work shift, a vacation, any other absence or a DIFFERENT kind of sick
 * leave does. Kinds are declared on the categories (sick_leave_kind, V39) —
 * never inferred from codes or names.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeLeaveService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");

    /** Thirty calendar days of STANDARD sick leave; the 31st is EXTENDED. */
    private static final int EXTENDED_AFTER_DAYS = 30;

    /**
     * How far back the continuity scan looks. It only has to DECIDE whether a
     * run has passed thirty days, so anything comfortably past the threshold is
     * enough; a run older than the window reports the window's edge as its
     * start, which is already beyond the threshold either way.
     */
    private static final int LOOKBACK_DAYS = 62;

    /** A period longer than a year is a typo, not a leave. */
    private static final int MAX_PERIOD_DAYS = 366;

    static final String KIND_STANDARD = "STANDARD";
    static final String KIND_EXTENDED = "EXTENDED";

    /** Godišnji odmor — the one non-sick absence a period may be entered for. */
    private static final String VACATION_CODE = "GO";

    private final EmployeeLeavePeriodRepository leaveRepository;
    private final EmployeeRepository employeeRepository;
    private final WorkShiftRepository workShiftRepository;
    private final WorkCodeCategoryRepository categoryRepository;
    private final ShiftRepository shiftRepository;
    private final WorkCalendarDayRepository workCalendarDayRepository;
    private final AbsenceRecordRepository absenceRecordRepository;
    private final AbsenceLogWriter absenceLogWriter;
    private final EmployeeRecordService employeeRecordService;
    private final WorkShiftService workShiftService;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final RecalcQueueService recalcQueueService;
    private final CurrentUserService currentUserService;
    private final EntityReferenceProvider referenceProvider;
    private final ApplicationEventPublisher eventPublisher;

    // ── Reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LeavePreviewResponse preview(LeaveRequest request) {
        Employee employee = requireActiveEmployee(request.employeeId());
        WorkCodeCategory category = requireLeaveCategory(request.workCodeCategoryId());
        Plan plan = computePlan(employee, category, request.dateFrom(), request.dateTo(),
                request.includeSaturdays());
        return toPreviewResponse(plan);
    }

    @Transactional(readOnly = true)
    public List<LeavePeriodDto> periodsForMonth(Long employeeId, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return leaveRepository.findIntersecting(employeeId, ym.atDay(1), ym.atEndOfMonth())
                .stream()
                .map(EmployeeLeaveService::toPeriodDto)
                .toList();
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * Execute the plan the preview showed.
     *
     * <p>Conflicting shifts are archived only with {@code archiveConflicts} —
     * the consent the preview asked for. Months whose payroll is handed over
     * are skipped whole and returned, so the screen can offer the payroll
     * change request; when EVERY month of the period is closed the apply is
     * refused instead, because it would have written nothing at all.
     */
    @Transactional
    public LeaveApplyResponse apply(LeaveRequest request) {
        Employee employee = requireActiveEmployee(request.employeeId());
        WorkCodeCategory category = requireLeaveCategory(request.workCodeCategoryId());
        Plan plan = computePlan(employee, category, request.dateFrom(), request.dateTo(),
                request.includeSaturdays());

        if (plan.allMonthsClosed()) {
            throw new ConflictException(
                    "Obračuni za sve mesece u periodu su predati ili zaključani."
                            + " Pošaljite zahtev za vraćanje obračuna na doradu pa unesite odsustvo.");
        }
        if (plan.conflictDays() > 0 && !request.archiveConflicts()) {
            throw new ConflictException(
                    "U periodu postoje smene drugog tipa (" + plan.conflictDays()
                            + " " + (plan.conflictDays() == 1 ? "dan" : "dana")
                            + "). Pregledajte ih i potvrdite arhiviranje pre unosa.");
        }
        if (plan.extendedWithoutHistory() && !request.acceptExtendedWarning()) {
            throw new ConflictException(
                    "Radnik pre ovog perioda nema 30 kalendarskih dana bolovanja u kontinuitetu,"
                            + " a izabrano je bolovanje preko 30 dana. Potvrdite da ste sigurni.");
        }

        Shift template = defaultShiftTemplate();
        String archiveReason = "Unos odsustva " + category.getCategoryNo()
                + " za period " + request.dateFrom() + " – " + request.dateTo();

        int created = 0;
        int archived = 0;
        Set<YearMonth> touchedMonths = new LinkedHashSet<>();

        for (PlannedDay day : plan.days()) {
            switch (day.status()) {
                case CONFLICT -> {
                    for (WorkShift shift : day.conflicts()) {
                        workShiftService.archive(shift.getId(), archiveReason);
                        archived++;
                    }
                    /*
                     * Flushed BEFORE the replacement is inserted. Hibernate
                     * orders inserts ahead of updates at flush time, and the
                     * overlap exclusion only ignores rows already archived —
                     * without this the new shift could reach the table while
                     * the old one still counts, and the constraint would refuse
                     * the very replacement the user just consented to.
                     */
                    workShiftRepository.flush();
                    // A same-type shift may stand beside the archived one; the
                    // day already holds the leave then, so nothing is created.
                    if (!day.hasSameType()) {
                        createLeaveShift(employee, day.date(), template, day.effectiveCategory());
                        created++;
                    }
                    touchedMonths.add(YearMonth.from(day.date()));
                }
                case CREATE -> {
                    createLeaveShift(employee, day.date(), template, day.effectiveCategory());
                    created++;
                    touchedMonths.add(YearMonth.from(day.date()));
                }
                default -> { /* skipped or closed */ }
            }
        }

        EmployeeLeavePeriod period = leaveRepository.save(EmployeeLeavePeriod.builder()
                .employee(employee)
                .workCodeCategory(category)
                .dateFrom(request.dateFrom())
                .dateTo(request.dateTo())
                .note(request.note())
                .createdBy(currentUserService.getCurrentUserId())
                .build());

        invalidateMonths(employee, touchedMonths, "LEAVE_PERIOD");

        log.info("Leave period {} ({} {} – {}) for employee {}: {} shifts created, {} archived",
                period.getId(), category.getCategoryNo(), request.dateFrom(), request.dateTo(),
                employee.getId(), created, archived);

        return new LeaveApplyResponse(period.getId(), created, archived, plan.closedMonths());
    }

    /**
     * Withdraw the RECORD of a period. The shifts it created stand — they are
     * the material truth a payroll may already have read; removing them is what
     * range-archiving is for.
     */
    @Transactional
    public void archivePeriod(Long id) {
        EmployeeLeavePeriod period = leaveRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Period odsustva ne postoji: " + id));
        if (period.getArchivedAt() != null) {
            return;
        }
        period.setArchivedAt(OffsetDateTime.now());
        period.setArchivedBy(currentUserService.getCurrentUserId());
        leaveRepository.save(period);
    }

    /**
     * The karton-creation sweep: fill in the leave days a month is owed by the
     * periods that reach into it.
     *
     * <p>Idempotent — a day already holding the period's category is skipped, so
     * running it for a month the apply already covered writes nothing. Conflicts
     * are REPORTED, never archived: archiving needs a person's consent, which a
     * bulk creation does not carry. The thirty-day rule runs here exactly as at
     * entry, so a sick leave crossing into the new month escalates to EXTENDED
     * on schedule.
     */
    @Transactional
    public MonthMaterialization materializeForMonth(Employee employee, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        List<EmployeeLeavePeriod> periods =
                leaveRepository.findIntersecting(employee.getId(), ym.atDay(1), ym.atEndOfMonth());
        if (periods.isEmpty()) {
            return new MonthMaterialization(0, List.of());
        }

        Shift template = defaultShiftTemplate();
        int created = 0;
        List<MaterializationConflict> conflicts = new ArrayList<>();
        Set<YearMonth> touchedMonths = new LinkedHashSet<>();

        for (EmployeeLeavePeriod period : periods) {
            LocalDate from = period.getDateFrom().isBefore(ym.atDay(1)) ? ym.atDay(1) : period.getDateFrom();
            LocalDate to = period.getDateTo().isAfter(ym.atEndOfMonth()) ? ym.atEndOfMonth() : period.getDateTo();
            Plan plan = computePlan(employee, period.getWorkCodeCategory(), from, to, false);

            List<LocalDate> conflictDates = new ArrayList<>();
            for (PlannedDay day : plan.days()) {
                switch (day.status()) {
                    case CREATE -> {
                        createLeaveShift(employee, day.date(), template, day.effectiveCategory());
                        created++;
                        touchedMonths.add(YearMonth.from(day.date()));
                    }
                    case CONFLICT -> conflictDates.add(day.date());
                    default -> { /* skipped or closed */ }
                }
            }
            if (!conflictDates.isEmpty()) {
                conflicts.add(new MaterializationConflict(
                        employee.getId(), employee.getFullName(),
                        period.getWorkCodeCategory().getCategoryNo(), conflictDates));
            }
        }

        invalidateMonths(employee, touchedMonths, "LEAVE_MATERIALIZED_ON_RECORD_CREATE");
        return new MonthMaterialization(created, conflicts);
    }

    /**
     * The whole month's sweep — every employee whose leave period reaches into
     * it. Called after "Kreiraj kartone" so a sick leave or vacation entered
     * last month continues into the new one the moment its kartoni exist.
     * A separate door from {@link #materializeForMonth} only in scope; the same
     * idempotent planner does the work.
     */
    @Transactional
    public MonthMaterialization materializeForMonthAll(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        Set<Long> seen = new LinkedHashSet<>();
        int created = 0;
        List<MaterializationConflict> conflicts = new ArrayList<>();
        for (EmployeeLeavePeriod period : leaveRepository.findAllIntersecting(ym.atDay(1), ym.atEndOfMonth())) {
            Employee employee = period.getEmployee();
            if (!seen.add(employee.getId())) {
                continue; // this employee's periods were all handled in one pass
            }
            if (!employee.isActive() || employee.getArchivedAt() != null) {
                continue;
            }
            MonthMaterialization result = materializeForMonth(employee, year, month);
            created += result.createdShifts();
            conflicts.addAll(result.conflicts());
        }
        return new MonthMaterialization(created, conflicts);
    }

    // ── The plan ─────────────────────────────────────────────────────────────

    enum DayStatus { CREATE, SKIP_WEEKEND, SKIP_NON_WORKING, SKIP_SAME_TYPE, CONFLICT, CLOSED_MONTH }

    record PlannedDay(LocalDate date, DayStatus status, boolean extended, Integer streakDay,
                      String holidayLabel, List<WorkShift> conflicts, boolean hasSameType,
                      WorkCodeCategory effectiveCategory) {
    }

    record Plan(List<PlannedDay> days, boolean extendedWithoutHistory, List<ClosedMonth> closedMonths,
                WorkCodeCategory extendedCategory, boolean allMonthsClosed) {

        int conflictDays() {
            return (int) days.stream().filter(d -> d.status() == DayStatus.CONFLICT).count();
        }

        int toCreate() {
            return (int) days.stream()
                    .filter(d -> d.status() == DayStatus.CREATE
                            || (d.status() == DayStatus.CONFLICT && !d.hasSameType()))
                    .count();
        }
    }

    private Plan computePlan(Employee employee, WorkCodeCategory chosen, LocalDate from, LocalDate to,
                             boolean includeSaturdays) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Period nije ispravan: datum od mora biti pre ili jednak datumu do.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException("Period je predugačak (najviše godinu dana).");
        }

        LocalDate scanFrom = from.minusDays(LOOKBACK_DAYS);
        Map<LocalDate, List<WorkShift>> shiftsByDate = new LinkedHashMap<>();
        for (WorkShift shift : workShiftRepository.findActiveWithCategoryInRange(employee.getId(), scanFrom, to)) {
            shiftsByDate.computeIfAbsent(shift.getWorkDate(), d -> new ArrayList<>()).add(shift);
        }
        Map<LocalDate, WorkCalendarDay> calByDate = new LinkedHashMap<>();
        for (WorkCalendarDay day : workCalendarDayRepository
                .findByCalendarDateBetweenOrderByCalendarDateAsc(scanFrom, to)) {
            calByDate.put(day.getCalendarDate(), day);
        }

        // Which months of the period are already handed to payroll.
        List<ClosedMonth> closedMonths = new ArrayList<>();
        Set<YearMonth> closed = new LinkedHashSet<>();
        for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
            if (payrollRunItemRepository.countClosedForEmployeeAndMonth(
                    employee.getId(), ym.getYear(), ym.getMonthValue()) > 0) {
                closed.add(ym);
                PayrollRunItem item = payrollRunItemRepository
                        .findByEmployee_IdAndPeriod(employee.getId(), ym.atDay(1))
                        .stream().findFirst().orElse(null);
                closedMonths.add(new ClosedMonth(ym.getYear(), ym.getMonthValue(),
                        item != null ? item.getId() : null,
                        item != null ? item.getStatus() : null));
            }
        }

        boolean trackStandard = KIND_STANDARD.equals(chosen.getSickLeaveKind());
        WorkCodeCategory extendedCategory = trackStandard || KIND_EXTENDED.equals(chosen.getSickLeaveKind())
                ? categoryRepository.findFirstBySickLeaveKindAndIsActiveTrueAndArchivedAtIsNull(KIND_EXTENDED)
                        .orElse(null)
                : null;
        if (trackStandard && extendedCategory == null) {
            // The rule cannot fire without its target; entered days stay STANDARD.
            log.warn("No active EXTENDED sick-leave category; the thirty-day rule is inert.");
            trackStandard = false;
        }

        LocalDate runStart = seedRunStart(from, shiftsByDate, calByDate);
        boolean extendedWithoutHistory = KIND_EXTENDED.equals(chosen.getSickLeaveKind())
                && streakLength(runStart, from.minusDays(1)) < EXTENDED_AFTER_DAYS;

        List<PlannedDay> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<WorkShift> onDay = shiftsByDate.getOrDefault(d, List.of());
            WorkCalendarDay calDay = calByDate.get(d);
            /*
             * Two notions of "working", kept apart on purpose. The run (the
             * thirty-day rule) always uses the strict calendar — a Saturday
             * nobody was expected in never breaks a sick-leave run, whichever
             * door the entry came through. Whether the day GETS a leave shift
             * may additionally count Saturdays, when the request asks for it.
             */
            boolean runWorking = isWorkingDay(d, calDay);
            boolean working = includeSaturdays
                    ? isWorkingDayCountingSaturday(d, calDay)
                    : runWorking;
            String holidayLabel = holidayLabelOf(calDay);

            if (closed.contains(YearMonth.from(d))) {
                // Left exactly as it stands; the run follows what EXISTS there.
                runStart = advanceRunOverExisting(runStart, d, onDay, runWorking);
                days.add(new PlannedDay(d, DayStatus.CLOSED_MONTH, false, null, holidayLabel,
                        List.of(), false, chosen));
                continue;
            }

            if (!onDay.isEmpty()) {
                boolean hasSameType = onDay.stream().anyMatch(ws -> hasCategory(ws, chosen.getId()));
                List<WorkShift> mismatched = onDay.stream()
                        .filter(ws -> !hasCategory(ws, chosen.getId()))
                        .toList();
                Integer streakDay = null;
                boolean extended = false;
                if (trackStandard) {
                    // After the apply the day holds this sick leave either way.
                    if (runStart == null) runStart = d;
                    streakDay = streakLength(runStart, d);
                    extended = streakDay > EXTENDED_AFTER_DAYS;
                }
                days.add(mismatched.isEmpty()
                        ? new PlannedDay(d, DayStatus.SKIP_SAME_TYPE, false, streakDay, holidayLabel,
                                List.of(), true, chosen)
                        : new PlannedDay(d, DayStatus.CONFLICT, extended, streakDay, holidayLabel,
                                mismatched, hasSameType,
                                extended ? extendedCategory : chosen));
                continue;
            }

            if (!working) {
                boolean weekend = (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)
                        && (calDay == null || calDay.getDayType() == WorkCalendarDayType.NON_WORKING);
                // The run survives a day nobody was expected in.
                days.add(new PlannedDay(d, weekend ? DayStatus.SKIP_WEEKEND : DayStatus.SKIP_NON_WORKING,
                        false, null, holidayLabel, List.of(), false, chosen));
                continue;
            }

            Integer streakDay = null;
            boolean extended = false;
            if (trackStandard) {
                if (runStart == null) runStart = d;
                streakDay = streakLength(runStart, d);
                extended = streakDay > EXTENDED_AFTER_DAYS;
            }
            days.add(new PlannedDay(d, DayStatus.CREATE, extended, streakDay, holidayLabel,
                    List.of(), false, extended ? extendedCategory : chosen));
        }

        boolean allClosed = !closed.isEmpty()
                && closed.size() == ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;

        return new Plan(days, extendedWithoutHistory, closedMonths,
                extendedCategory, allClosed);
    }

    /**
     * Where the continuous sick-leave run the period joins actually began, or
     * null when there is none. Walks back from the day before the period:
     * sick-leave days extend the run, non-working days are stepped over, and a
     * working day holding anything else — or nothing at all — ends it.
     */
    private LocalDate seedRunStart(LocalDate from,
                                   Map<LocalDate, List<WorkShift>> shiftsByDate,
                                   Map<LocalDate, WorkCalendarDay> calByDate) {
        LocalDate runStart = null;
        LocalDate floor = from.minusDays(LOOKBACK_DAYS);
        for (LocalDate d = from.minusDays(1); !d.isBefore(floor); d = d.minusDays(1)) {
            List<WorkShift> onDay = shiftsByDate.get(d);
            if (onDay != null && !onDay.isEmpty()) {
                if (!onDay.stream().allMatch(ws -> continuesSickRun(ws.getWorkCodeCategory()))) {
                    break;
                }
                runStart = d;
            } else if (isWorkingDay(d, calByDate.get(d))) {
                break;
            }
        }
        return runStart;
    }

    /** The run's fate across a day this apply may not touch (closed month). */
    private static LocalDate advanceRunOverExisting(LocalDate runStart, LocalDate d,
                                                    List<WorkShift> onDay, boolean working) {
        if (!onDay.isEmpty()) {
            if (onDay.stream().allMatch(ws -> continuesSickRun(ws.getWorkCodeCategory()))) {
                return runStart == null ? d : runStart;
            }
            return null;
        }
        return working ? null : runStart;
    }

    /** Calendar days from the run's start through {@code d}, or 0 without a run. */
    private static int streakLength(LocalDate runStart, LocalDate d) {
        if (runStart == null || d.isBefore(runStart)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(runStart, d) + 1;
    }

    private static boolean continuesSickRun(WorkCodeCategory category) {
        return category != null
                && (KIND_STANDARD.equals(category.getSickLeaveKind())
                        || KIND_EXTENDED.equals(category.getSickLeaveKind()));
    }

    private static boolean hasCategory(WorkShift shift, Long categoryId) {
        return shift.getWorkCodeCategory() != null
                && categoryId.equals(shift.getWorkCodeCategory().getId());
    }

    private static boolean isWorkingDay(LocalDate d, WorkCalendarDay calDay) {
        if (calDay != null) {
            return WorkCalendarDayEffectiveStatus.isWorking(calDay);
        }
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * The quick single-day entry's calendar: a plain Saturday counts as worked
     * (the factory normally works Saturdays — the same convention the bonus
     * calculation encodes), while Sundays, holidays and explicit overrides keep
     * their say.
     */
    private static boolean isWorkingDayCountingSaturday(LocalDate d, WorkCalendarDay calDay) {
        if (calDay != null) {
            return WorkCalendarDayEffectiveStatus.isWorkingForBonusPurposes(calDay);
        }
        return d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private static String holidayLabelOf(WorkCalendarDay calDay) {
        if (calDay == null) {
            return null;
        }
        if (calDay.getDayType() == WorkCalendarDayType.HOLIDAY) {
            return calDay.getLabel() != null ? calDay.getLabel() : "Praznik";
        }
        if (calDay.getDayType() == WorkCalendarDayType.COLLECTIVE_LEAVE) {
            return calDay.getLabel() != null ? calDay.getLabel() : "Kolektivni odmor";
        }
        return null;
    }

    // ── The rows a leave day is made of ──────────────────────────────────────

    /**
     * One leave day: the shift the karton shows, and the absence record the
     * daily recalculation prices. The same shape a whole-day NO already has —
     * see {@code ShiftAbsenceSync} — so every report and payroll path that
     * understands an absence understands this day with no new code.
     */
    private void createLeaveShift(Employee employee, LocalDate date, Shift template,
                                  WorkCodeCategory category) {
        OffsetDateTime startAt = LocalDateTime.of(date, template.getStartTime()).atZone(ZONE).toOffsetDateTime();
        OffsetDateTime endAt = LocalDateTime.of(date, template.getEndTime()).atZone(ZONE).toOffsetDateTime();
        if (!endAt.isAfter(startAt)) {
            endAt = endAt.plusDays(1);
        }

        EmployeeRecord record = employeeRecordService.getOrCreateMonthlyRecord(employee.getId(), date);
        Long userId = currentUserService.getCurrentUserId();

        WorkShift shift = workShiftRepository.save(WorkShift.builder()
                .employee(employee)
                .employeeRecord(record)
                .shift(template)
                .supervisor(userId != null
                        ? referenceProvider.getRequiredReference(User.class, userId, "supervisorId")
                        : null)
                .workCodeCategory(category)
                .startAt(startAt)
                .endAt(endAt)
                .workDate(date)
                .isActive(true)
                .build());

        absenceRecordRepository.save(AbsenceRecord.builder()
                .employee(employee)
                .workShift(shift)
                .workCodeCategory(category)
                .startAt(startAt)
                .endAt(endAt)
                .absenceMinutes((int) Duration.between(startAt, endAt).toMinutes())
                .normMultiplierSnapshot(BigDecimal.valueOf(
                        category.getNormMultiplier() == null ? 0d : category.getNormMultiplier()))
                .paidMinutes(0)
                .compensatedMinutes(0)
                .createdBy(userId)
                .isActive(true)
                .build());

        /*
         * The DAY, drawn. A full-day absence shows on the shift as one operation
         * spanning it — exactly as NO and ND do — so the karton reads as a day
         * off rather than an empty shift. The recalculation drops this log the
         * moment it sees the category is full-day (V40), so the minutes are still
         * priced once, through the absence record above.
         */
        absenceLogWriter.ensureFullDayLog(shift, category);

        recalcQueueService.enqueueDailyJob(shift, "LEAVE_PERIOD");
    }

    /**
     * Bump what the months are calculated from: the payroll items learn their
     * inputs moved (LOCKED ones excluded, as everywhere), and the monthly
     * rebuild is queued. The daily jobs were queued per created shift already.
     */
    private void invalidateMonths(Employee employee, Set<YearMonth> months, String reason) {
        if (months.isEmpty()) {
            return;
        }
        for (YearMonth ym : months) {
            payrollRunItemRepository.markNeedsRecalculationByEmployeeAndMonth(
                    employee.getId(), ym.getYear(), ym.getMonthValue());
            recalcQueueService.enqueueMonthlyJob(employee, ym.getYear(), ym.getMonthValue(), reason);
        }
        eventPublisher.publishEvent(new DailyRecalcRequestedEvent(DailyRecalcRequestedEvent.Type.DAILY));
    }

    // ── Guards and mapping ───────────────────────────────────────────────────

    private Employee requireActiveEmployee(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Radnik ne postoji: " + employeeId));
        if (!employee.isActive() || employee.getArchivedAt() != null) {
            throw new ConflictException("Radnik nije aktivan, pa mu se odsustvo ne može uneti.");
        }
        return employee;
    }

    /**
     * Only two things may be entered for a whole period: sick leave (any kind)
     * and godišnji odmor. Everything else an absence can be — a non-working day
     * the bank writes, an hour of paid leave through the absence dialog — is
     * recorded some other way, and offering it here would be a second, worse
     * door to it.
     */
    private WorkCodeCategory requireLeaveCategory(Long categoryId) {
        WorkCodeCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new EntityNotFoundException("Kategorija ne postoji: " + categoryId));
        boolean allowed = "SICK_LEAVE".equalsIgnoreCase(category.getType())
                || VACATION_CODE.equals(category.getCategoryNo());
        if (!allowed || !Boolean.TRUE.equals(category.getIsActive()) || category.getArchivedAt() != null) {
            throw new ConflictException("Za period se može uneti samo godišnji odmor ili bolovanje — kategorija "
                    + category.getCategoryNo() + " nije dozvoljena.");
        }
        return category;
    }

    private Shift defaultShiftTemplate() {
        return shiftRepository.findByIsActiveTrueAndArchivedAtIsNullOrderByStartTimeAsc()
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Nijedna smena nije definisana."));
    }

    private LeavePreviewResponse toPreviewResponse(Plan plan) {
        List<LeaveDayPlan> days = plan.days().stream()
                .map(d -> new LeaveDayPlan(
                        d.date(),
                        d.status().name(),
                        d.extended(),
                        d.streakDay(),
                        d.holidayLabel(),
                        d.conflicts().stream()
                                .map(ws -> new ConflictShift(
                                        ws.getId(),
                                        ws.getShift() != null ? ws.getShift().getName() : null,
                                        ws.getWorkCodeCategory() != null ? ws.getWorkCodeCategory().getCategoryNo() : null,
                                        ws.getWorkCodeCategory() != null ? ws.getWorkCodeCategory().getCategoryName() : null))
                                .toList()))
                .toList();
        return new LeavePreviewResponse(
                days,
                plan.toCreate(),
                plan.conflictDays(),
                plan.extendedWithoutHistory(),
                plan.extendedCategory() != null ? plan.extendedCategory().getCategoryName() : null,
                defaultShiftTemplate().getName(),
                plan.closedMonths());
    }

    private static LeavePeriodDto toPeriodDto(EmployeeLeavePeriod period) {
        return new LeavePeriodDto(
                period.getId(),
                period.getEmployee().getId(),
                period.getWorkCodeCategory().getId(),
                period.getWorkCodeCategory().getCategoryNo(),
                period.getWorkCodeCategory().getCategoryName(),
                period.getDateFrom(),
                period.getDateTo(),
                period.getNote(),
                period.getCreatedAt());
    }
}
