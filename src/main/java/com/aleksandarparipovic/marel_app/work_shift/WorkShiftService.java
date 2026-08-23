package com.aleksandarparipovic.marel_app.work_shift;

import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.jpa.EntityReferenceProvider;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecord;
import com.aleksandarparipovic.marel_app.employee_record.EmployeeRecordService;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.report_worker.DailyRecalcRequestedEvent;
import com.aleksandarparipovic.marel_app.shift.Shift;
import com.aleksandarparipovic.marel_app.shift.ShiftRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogDto;
import com.aleksandarparipovic.marel_app.work_log.dto.WorkLogPreviewDto;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.dto.*;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aleksandarparipovic.marel_app.common.ConflictException;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class WorkShiftService {

    private final WorkShiftRepository repository;
    private final CurrentUserService currentUserService;
    private final WorkLogRepository workLogRepository;
    private final WorkShiftMapper workShiftMapper;
    private final ShiftRepository shiftRepository;
    private final EmployeeRecordService employeeRecordService;
    private final EntityReferenceProvider referenceProvider;
    private final RecalcQueueService recalcQueueService;
    private final ApplicationEventPublisher eventPublisher;
    private final DailyReportRepository dailyReportRepository;
    private final com.aleksandarparipovic.marel_app.daily_report_category.DailyReportCategoryRepository dailyReportCategoryRepository;
    private final com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository payrollRunItemRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");


    public WorkShiftBasicInfoDto getWorkShiftById(Long id){
        return repository.findById(id)
                .map(workShiftMapper::toBasicInfoDto)
                .orElseThrow(() -> new EntityNotFoundException("Shift not found"));
    }

    public WorkShiftInfoDto getWorkShiftInfo(Long id) {
        WorkShift ws = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Shift not found"));
        DailyReport dr = dailyReportRepository.findByWorkShiftId(id).orElse(null);
        return workShiftMapper.toInfoDto(ws, dr);
    }

    /** Years the Kartoni view may offer: every year that holds at least one live shift. */
    public List<Integer> findYearsWithShifts(){
        return repository.findYearsWithShifts();
    }

    public List<WorkShiftActivityDto> findLastThreePerMonthForSupervisor(int year){
        Long userId = currentUserService.getCurrentUserId();
        return repository.findLastThreePerMonthForSupervisor(userId, year);
    }

    public Page<WorkShiftInfo> getWorkShiftsByYearAndMonth(
            Integer year,
            Integer month,
            String search,
            Pageable pageable
    ) {
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);

        return repository.findMonthlyShifts(start, end, search, pageable);
    }


    public List<WorkShiftWithLogsPreviewDto> getShiftsPreviewForEmployee(Long employeeId, int year, int month){
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);
        List<WorkShiftDetailInfo> shifts =
                repository.getShiftsForMonth(employeeId, start, end);

        List<Long> shiftIds = shifts.stream()
                .map(WorkShiftDetailInfo::getId)
                .toList();

        List<WorkLogPreviewDto> logs =
                workLogRepository.getLogsPreviewForShifts(shiftIds);

        Map<Long, List<WorkLogPreviewDto>> logsByShift =
                logs.stream()
                        .collect(Collectors.groupingBy(WorkLogPreviewDto::getShiftId));

        return shifts.stream()
                .map(shift -> new WorkShiftWithLogsPreviewDto(
                        shift.getId(),
                        shift.getWorkDate(),
                        shift.getSupervisorId(),
                        shift.getSupervisorFullName(),
                        shift.getStartAt(),
                        shift.getEndAt(),
                        shift.getTotalMinutes(),
                        shift.getNote(),
                        shift.getEmployeeId(),
                        shift.getEmployeeName(),
                        logsByShift.getOrDefault(shift.getId(), List.of())
                ))
                .toList();
    }

    public List<WorkShiftWithLogsDto> getShiftsForEmployee(Long employeeId, int year, int month){
        OffsetDateTime start = YearMonth.of(year, month).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusMonths(1);
        List<WorkShiftDetailInfo> shifts =
                repository.getShiftsForMonth(employeeId, start, end);

        List<Long> shiftIds = shifts.stream()
                .map(WorkShiftDetailInfo::getId)
                .toList();

        List<WorkLogDto> logs =
                workLogRepository.getLogsForShifts(shiftIds);

        Map<Long, List<WorkLogDto>> logsByShift =
                logs.stream()
                        .collect(Collectors.groupingBy(WorkLogDto::getShiftId));

        return shifts.stream()
                .map(shift -> new WorkShiftWithLogsDto(
                        shift.getId(),
                        shift.getWorkDate(),
                        shift.getSupervisorId(),
                        shift.getSupervisorFullName(),
                        shift.getStartAt(),
                        shift.getEndAt(),
                        shift.getTotalMinutes(),
                        shift.getNote(),
                        shift.getEmployeeId(),
                        shift.getEmployeeName(),
                        logsByShift.getOrDefault(shift.getId(), List.of())
                ))
                .toList();
    }

    public List<Long> getShiftsForEmployeeRecord(Long employeeRecordId) {
        return repository.getShiftsForEmployeeRecord(employeeRecordId);
    }

    public List<Long> getShiftsForEmployeeRecord(Long employeeRecordId, LocalDate fromDate, LocalDate toDate) {
        if (fromDate != null && toDate != null) {
            LocalDate start = fromDate.isBefore(toDate) ? fromDate : toDate;
            LocalDate end = fromDate.isBefore(toDate) ? toDate : fromDate;
            return repository.getShiftsForEmployeeRecordInDateRange(employeeRecordId, start, end);
        }

        if (fromDate != null || toDate != null) {
            LocalDate exactDate = fromDate != null ? fromDate : toDate;
            return repository.getShiftsForEmployeeRecordOnDate(employeeRecordId, exactDate);
        }

        return repository.getShiftsForEmployeeRecord(employeeRecordId);
    }

    @Transactional


    /** The interval a trimmed shift would occupy. */
    private record Interval(OffsetDateTime start, OffsetDateTime end) {}

    /**
     * The new shift with the collision cut off it, or empty when nothing is left.
     *
     * <p>THE COLLISION HAS TWO SIDES and the first version only handled one. A
     * third shift 22:00–06:00 running into a first shift that starts at 05:00 is
     * cut at the END. A second shift 14:00–22:00 running into that same first
     * shift, which ends at 14:40, has to be cut at the START — and was offered
     * nothing but a merge, because the existing shift did not begin after it.
     *
     * <p>Neither cut is possible when the existing shift swallows the new one
     * whole; merging is then the only thing left that means anything.
     */
    private static Optional<Interval> trimmedAround(OffsetDateTime startAt,
                                                    OffsetDateTime endAt,
                                                    WorkShift other) {
        // The other one starts inside ours: keep the part before it.
        if (other.getStartAt().isAfter(startAt)) {
            return Optional.of(new Interval(startAt, other.getStartAt()));
        }
        // The other one covers our start: begin where it ends, if that leaves time.
        if (other.getEndAt().isBefore(endAt)) {
            return Optional.of(new Interval(other.getEndAt(), endAt));
        }
        return Optional.empty();
    }

    /**
     * Turn a collision into a question: what is in the way, and what could be done.
     *
     * <p>Both ways out are offered only when exactly ONE shift is in the way.
     * With several, trimming would have to choose a neighbour and merging would
     * have to swallow somebody else's record — guesses whose cost is measured in
     * somebody's pay.
     */
    private WorkShiftOverlapException overlapQuestion(List<WorkShift> overlapping,
                                                      OffsetDateTime startAt,
                                                      OffsetDateTime endAt) {
        List<WorkShiftOverlapException.Conflict> conflicts = overlapping.stream()
                .map(ws -> new WorkShiftOverlapException.Conflict(
                        ws.getId(), ws.getShift().getName(), ws.getStartAt(), ws.getEndAt()))
                .toList();

        if (overlapping.size() == 2) {
            return betweenTwo(overlapping, startAt, endAt, conflicts);
        }
        if (overlapping.size() > 1) {
            return new WorkShiftOverlapException(
                    "Smena se preklapa sa više postojećih smena. Ispravite ih pojedinačno.",
                    conflicts, List.of());
        }

        WorkShift other = overlapping.getFirst();
        List<WorkShiftOverlapException.Option> options = new ArrayList<>();

        trimmedAround(startAt, endAt, other).ifPresent(trimmed -> options.add(
                new WorkShiftOverlapException.Option(
                        WorkShiftOverlapException.Resolution.TRIM,
                        trimmed.start(), trimmed.end(),
                        trimmed.start().isEqual(startAt)
                                ? "Skrati novu smenu do početka postojeće"
                                : "Pomeri početak nove smene na kraj postojeće")));

        // Merge: the existing shift stretched over both. It keeps its id, its type
        // and its work logs — it is the one that already has them.
        OffsetDateTime mergedStart = startAt.isBefore(other.getStartAt()) ? startAt : other.getStartAt();
        OffsetDateTime mergedEnd   = endAt.isAfter(other.getEndAt())      ? endAt   : other.getEndAt();
        options.add(new WorkShiftOverlapException.Option(
                WorkShiftOverlapException.Resolution.MERGE,
                mergedStart, mergedEnd,
                "Spoji u jednu smenu"));

        return new WorkShiftOverlapException(
                "Smena se preklapa sa postojećom smenom \"" + other.getShift().getName() + "\".",
                conflicts, options);
    }


    /** The shift covering the new one's start, and the one beginning inside it. */
    private static WorkShift previousOf(List<WorkShift> overlapping, OffsetDateTime startAt) {
        return overlapping.stream()
                .filter(ws -> !ws.getStartAt().isAfter(startAt))
                .findFirst().orElse(null);
    }

    private static WorkShift nextOf(List<WorkShift> overlapping, OffsetDateTime startAt) {
        return overlapping.stream()
                .filter(ws -> ws.getStartAt().isAfter(startAt))
                .findFirst().orElse(null);
    }

    /**
     * The new shift runs into one on EACH side.
     *
     * <p>Three ways out, and every one of them is collision-free by construction:
     * a merge stops exactly where the shift on the other side begins, so absorbing
     * the new shift never pushes the result into its neighbour.
     *
     * <ul>
     *   <li>MERGE_PREVIOUS — the earlier shift stretches forward to where the later
     *       one starts.</li>
     *   <li>MERGE_NEXT — the later shift stretches back to where the earlier one
     *       ends.</li>
     *   <li>FIT_BETWEEN — a new shift filling exactly the gap. Offered only when
     *       there IS a gap; when the two already touch, there is nothing to fill.</li>
     * </ul>
     *
     * <p>Two conflicts that are not one-before-one-after — both beginning inside
     * the new shift, say — get nothing. There is no "previous and next" to speak
     * of, and choosing for the user there would be guessing with their pay.
     */
    private WorkShiftOverlapException betweenTwo(List<WorkShift> overlapping,
                                                 OffsetDateTime startAt,
                                                 OffsetDateTime endAt,
                                                 List<WorkShiftOverlapException.Conflict> conflicts) {
        WorkShift previous = previousOf(overlapping, startAt);
        WorkShift next = nextOf(overlapping, startAt);

        if (previous == null || next == null) {
            return new WorkShiftOverlapException(
                    "Smena se preklapa sa više postojećih smena. Ispravite ih pojedinačno.",
                    conflicts, List.of());
        }

        List<WorkShiftOverlapException.Option> options = new ArrayList<>();

        options.add(new WorkShiftOverlapException.Option(
                WorkShiftOverlapException.Resolution.MERGE_PREVIOUS,
                previous.getStartAt(), next.getStartAt(),
                "Spoji sa prethodnom smenom (" + previous.getShift().getName() + ")"));

        options.add(new WorkShiftOverlapException.Option(
                WorkShiftOverlapException.Resolution.MERGE_NEXT,
                previous.getEndAt(), next.getEndAt(),
                "Spoji sa sledećom smenom (" + next.getShift().getName() + ")"));

        if (previous.getEndAt().isBefore(next.getStartAt())) {
            options.add(new WorkShiftOverlapException.Option(
                    WorkShiftOverlapException.Resolution.FIT_BETWEEN,
                    previous.getEndAt(), next.getStartAt(),
                    "Neka traje između njih"));
        }

        return new WorkShiftOverlapException(
                "Smena se preklapa sa smenama \"" + previous.getShift().getName()
                        + "\" i \"" + next.getShift().getName() + "\".",
                conflicts, options);
    }


    /**
     * Apply one of the three answers for a shift caught between two others.
     *
     * <p>Each result is bounded by the neighbour on the far side, so none of them
     * can trade one collision for another.
     */
    private WorkShiftBasicInfoDto resolveBetweenTwo(WorkShiftCreateRequest request,
                                                    List<WorkShift> overlapping,
                                                    OffsetDateTime startAt,
                                                    OffsetDateTime endAt,
                                                    String choice) {
        WorkShift previous = previousOf(overlapping, startAt);
        WorkShift next = nextOf(overlapping, startAt);
        if (previous == null || next == null) {
            throw overlapQuestion(overlapping, startAt, endAt);
        }

        return switch (choice) {
            case "MERGE_PREVIOUS" -> {
                // Forward only as far as the later shift begins.
                previous.setEndAt(next.getStartAt());
                yield workShiftMapper.toBasicInfoDto(repository.save(previous));
            }
            case "MERGE_NEXT" -> {
                // Back only as far as the earlier shift ends.
                next.setStartAt(previous.getEndAt());
                yield workShiftMapper.toBasicInfoDto(repository.save(next));
            }
            case "FIT_BETWEEN" -> {
                if (!previous.getEndAt().isBefore(next.getStartAt())) {
                    throw new ConflictException(
                            "Između postojećih smena nema slobodnog vremena.");
                }
                yield persistShift(request, previous.getEndAt(), next.getStartAt());
            }
            default -> throw new ConflictException(
                    "Nepoznat način rešavanja preklapanja: " + choice);
        };
    }

    /**
     * Apply the resolution the user picked, or return null when they have not
     * picked one yet.
     */
    private WorkShiftBasicInfoDto resolveOverlap(WorkShiftCreateRequest request,
                                                 List<WorkShift> overlapping,
                                                 OffsetDateTime startAt,
                                                 OffsetDateTime endAt) {
        String choice = request.getOverlapResolution();
        if (choice == null || choice.isBlank()) {
            return null;
        }
        if (overlapping.size() == 2) {
            return resolveBetweenTwo(request, overlapping, startAt, endAt, choice);
        }
        if (overlapping.size() > 1) {
            throw overlapQuestion(overlapping, startAt, endAt);
        }

        WorkShift other = overlapping.getFirst();
        WorkShiftOverlapException.Resolution resolution;
        try {
            resolution = WorkShiftOverlapException.Resolution.valueOf(choice);
        } catch (IllegalArgumentException ex) {
            throw new ConflictException("Nepoznat način rešavanja preklapanja: " + choice);
        }

        if (resolution == WorkShiftOverlapException.Resolution.MERGE) {
            // Stretch the EXISTING shift. Not a new row replacing it: this one
            // already carries the work logs, and creating a replacement would
            // orphan or destroy them.
            if (startAt.isBefore(other.getStartAt())) {
                other.setStartAt(startAt);
            }
            if (endAt.isAfter(other.getEndAt())) {
                other.setEndAt(endAt);
            }
            return workShiftMapper.toBasicInfoDto(repository.save(other));
        }

        // TRIM — exactly the interval the option advertised, computed by the same
        // method, so what the user was shown and what is saved cannot disagree.
        Interval trimmed = trimmedAround(startAt, endAt, other)
                .orElseThrow(() -> new ConflictException(
                        "Nova smena ne može da se skrati: postojeća smena je pokriva u celosti."));
        return persistShift(request, trimmed.start(), trimmed.end());
    }

    public WorkShiftBasicInfoDto createShift(WorkShiftCreateRequest request) {
        LocalDate workDate = LocalDate.parse(request.getWorkDate());

        Shift shift = shiftRepository.findById(request.getShiftType())
                .orElseThrow(() -> new EntityNotFoundException("Shift not found: " + request.getShiftType()));

        OffsetDateTime startAt = LocalDateTime.of(workDate, shift.getStartTime())
                .atZone(ZONE).toOffsetDateTime();
        OffsetDateTime endAt = LocalDateTime.of(workDate, shift.getEndTime())
                .atZone(ZONE).toOffsetDateTime();

        // Handle overnight shifts
        if (endAt.isBefore(startAt) || endAt.isEqual(startAt)) {
            endAt = endAt.plusDays(1);
        }

        // Ask before inserting. The exclusion constraint is the guarantee, but it
        // can only refuse — it cannot say which shift is in the way or offer a way
        // round it, and what reaches the user from it is a raw SQL error.
        List<WorkShift> overlapping = repository.findOverlapping(request.getEmployeeId(), startAt, endAt);
        if (!overlapping.isEmpty()) {
            WorkShiftBasicInfoDto resolved = resolveOverlap(request, overlapping, startAt, endAt);
            if (resolved != null) {
                return resolved;
            }
            // No resolution chosen yet — hand back the collision and the options.
            throw overlapQuestion(overlapping, startAt, endAt);
        }

        return persistShift(request, startAt, endAt);
    }

    /**
     * The row itself, with the interval given rather than derived.
     *
     * <p>Separate so a TRIMMED shift is built by exactly the same code as a whole
     * one — only its end differs. Everything else about it is the shift the user
     * asked for.
     */
    private WorkShiftBasicInfoDto persistShift(WorkShiftCreateRequest request,
                                               OffsetDateTime startAt,
                                               OffsetDateTime endAt) {
        LocalDate workDate = LocalDate.parse(request.getWorkDate());
        Shift shift = shiftRepository.findById(request.getShiftType())
                .orElseThrow(() -> new EntityNotFoundException("Shift not found: " + request.getShiftType()));

        EmployeeRecord employeeRecord = employeeRecordService.getOrCreateMonthlyRecord(request.getEmployeeId(), workDate);

        WorkShift workShift = WorkShift.builder()
                .employee(referenceProvider.getRequiredReference(Employee.class, request.getEmployeeId(), "employeeId"))
                .employeeRecord(employeeRecord)
                .shift(shift)
                .supervisor(referenceProvider.getRequiredReference(User.class, request.getSupervisorId(), "supervisorId"))
                .workCodeCategory(referenceProvider.getRequiredReference(WorkCodeCategory.class, request.getWorkCategoryCodeId(), "workCategoryCodeId"))
                .startAt(startAt)
                .endAt(endAt)
                .workDate(workDate)
                .isActive(true)
                .build();

        return workShiftMapper.toBasicInfoDto(repository.save(workShift));
    }

    @Transactional
    public WorkShiftBasicInfoDto updateShift(Long workShiftId, UpdateWorkShiftRequest request) {
        WorkShift workShift = repository.findById(workShiftId)
                .orElseThrow(() -> new EntityNotFoundException("Work shift not found: " + workShiftId));

        // Update supervisor
        workShift.setSupervisor(referenceProvider.getRequiredReference(User.class, request.getSupervisorId(), "supervisorId"));

        // Update work code category
        workShift.setWorkCodeCategory(referenceProvider.getRequiredReference(WorkCodeCategory.class, request.getWorkCategoryCodeId(), "workCategoryCodeId"));

        // Apply note value directly; null explicitly clears the note.
        workShift.setNote(request.getNotes());
        
        Shift shift = shiftRepository.findById(request.getShiftId())
                .orElseThrow(() -> new EntityNotFoundException("Shift not found: " + request.getShiftId()));

        OffsetDateTime startAt = LocalDateTime.of(workShift.getWorkDate(), shift.getStartTime())
                .atZone(ZONE)
                .toOffsetDateTime();
        OffsetDateTime endAt = LocalDateTime.of(workShift.getWorkDate(), shift.getEndTime())
                .atZone(ZONE)
                .toOffsetDateTime();

        // Keep overnight shifts spanning into the next day.
        if (endAt.isBefore(startAt) || endAt.isEqual(startAt)) {
            endAt = endAt.plusDays(1);
        }

        workShift.setShift(shift);
        workShift.setStartAt(startAt);
        workShift.setEndAt(endAt);

        WorkShift updated = repository.save(workShift);

        // Trigger recalculation if requested
        if (Boolean.TRUE.equals(request.getTriggerRecalculation())) {
            recalcQueueService.enqueueDailyJob(workShift, "WORK_SHIFT_UPDATE");
            eventPublisher.publishEvent(new DailyRecalcRequestedEvent(DailyRecalcRequestedEvent.Type.DAILY));
        }

        return workShiftMapper.toBasicInfoDto(updated);
    }

    /**
     * Keeps the shift's startAt/endAt in sync with its work logs: the boundary
     * expands to cover the earliest log start / latest log end, and shrinks back
     * toward the shift template's default start/end as logs are edited or removed.
     * Always recomputed from scratch (template ⨉ remaining active logs), so deleting
     * the log that caused an earlier expansion correctly pulls the boundary back in.
     * Note: this is the shift's clock-in/clock-out *window*, not "worked minutes" —
     * it intentionally falls back to the template's default span when no logs exist.
     */
    @Transactional
    public void recalculateShiftBoundaries(WorkShift workShift) {
        Shift shiftTemplate = workShift.getShift();

        OffsetDateTime templateStart = LocalDateTime.of(workShift.getWorkDate(), shiftTemplate.getStartTime())
                .atZone(ZONE).toOffsetDateTime();
        OffsetDateTime templateEnd = LocalDateTime.of(workShift.getWorkDate(), shiftTemplate.getEndTime())
                .atZone(ZONE).toOffsetDateTime();
        if (!templateEnd.isAfter(templateStart)) {
            templateEnd = templateEnd.plusDays(1);
        }

        WorkLogRepository.ActiveLogBounds bounds = workLogRepository.findActiveBoundsForShift(workShift.getId());

        OffsetDateTime newStart = templateStart;
        OffsetDateTime newEnd = templateEnd;
        if (bounds != null) {
            if (bounds.getMinStart() != null && bounds.getMinStart().isBefore(newStart)) {
                newStart = bounds.getMinStart();
            }
            if (bounds.getMaxEnd() != null && bounds.getMaxEnd().isAfter(newEnd)) {
                newEnd = bounds.getMaxEnd();
            }
        }

        if (!newStart.isEqual(workShift.getStartAt()) || !newEnd.isEqual(workShift.getEndAt())) {
            workShift.setStartAt(newStart);
            workShift.setEndAt(newEnd);
            repository.save(workShift);
        }
    }

    // ── Withdrawing a shift ──────────────────────────────────────────────────

    /**
     * Take a whole shift back.
     *
     * <p>ARCHIVED, NOT DELETED. The work logs on it are what somebody was paid
     * for, and a payroll that was calculated from them has to stay explainable.
     * The shift stops counting: its daily report goes, the month is requeued
     * without it, and the lists that already filter on is_active stop showing it.
     *
     * <p>Refused when the month's payroll is LOCKED. Everywhere else in this
     * system a locked month is skipped and the change still stands, because the
     * change is about the employee rather than the month. This one IS about the
     * month — the hours are what the locked payroll was built from — so accepting
     * it would leave a record of what was paid standing beside a report that no
     * longer supports it.
     */
    @Transactional
    public void archive(Long id, String reason) {
        WorkShift shift = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + id));

        if (shift.getArchivedAt() != null) {
            throw new ConflictException("Smena je već arhivirana.");
        }
        refuseWhenMonthIsLocked(shift);

        shift.setArchivedAt(OffsetDateTime.now());
        shift.setArchivedBy(currentUserService.getCurrentUserId());
        // Kept in step so every query already filtering on it keeps working.
        shift.setIsActive(false);
        shift.setNote(appendReason(shift.getNote(), reason));
        repository.save(shift);

        dropDailyReportAndRequeueMonth(shift);

        log.info("Work shift {} archived by user {} ({})", id,
                currentUserService.getCurrentUserId(), reason != null ? reason : "bez razloga");
    }

    /**
     * Put a withdrawn shift back.
     *
     * <p>The overlap and one-per-day rules count live shifts only, so restoring
     * one whose hours have since been taken by another is refused by the
     * database. Translated here into something readable rather than surfaced as
     * a constraint name.
     */
    @Transactional
    public void restore(Long id) {
        WorkShift shift = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + id));

        if (shift.getArchivedAt() == null) {
            return;
        }
        refuseWhenMonthIsLocked(shift);

        shift.setArchivedAt(null);
        shift.setArchivedBy(null);
        shift.setIsActive(true);
        try {
            repository.saveAndFlush(shift);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "Za taj dan već postoji smena koja se preklapa sa ovom. "
                            + "Uklonite ili pomerite nju pre vraćanja ove.");
        }

        // Rebuilt from its own logs, rather than restored from anything: the
        // report is derived data and the logs are still there.
        recalcQueueService.enqueueDailyJob(shift, "WORK_SHIFT_RESTORE");
        log.info("Work shift {} restored by user {}", id, currentUserService.getCurrentUserId());
    }

    /**
     * Delete a shift that never held anything.
     *
     * <p>The one case where deleting destroys no history: no work logs, no
     * absences, nothing that reached a payroll. Anything else is archived, and
     * the database enforces that too — the child tables are ON DELETE RESTRICT.
     */
    @Transactional
    public void deleteEmpty(Long id) {
        WorkShift shift = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + id));

        refuseWhenMonthIsLocked(shift);

        long children = countChildren(id);
        if (children > 0) {
            throw new ConflictException(
                    "Smena ima unete radne naloge ili odsustva i ne može se obrisati. "
                            + "Arhivirajte je — unosi ostaju zabeleženi.");
        }

        dailyReportRepository.findByWorkShiftId(id).ifPresent(dailyReportRepository::delete);
        repository.delete(shift);
        log.info("Empty work shift {} deleted by user {}", id, currentUserService.getCurrentUserId());
    }

    /** Work logs, absences and compensations — the three that block a delete. */
    private long countChildren(Long shiftId) {
        Number count = (Number) entityManager.createNativeQuery("""
                SELECT (SELECT count(*) FROM work_logs WHERE work_shift_id = :id)
                     + (SELECT count(*) FROM absence_records WHERE work_shift_id = :id)
                     + (SELECT count(*) FROM absence_compensations WHERE work_shift_id = :id)
                """)
                .setParameter("id", shiftId)
                .getSingleResult();
        return count != null ? count.longValue() : 0L;
    }

    private void refuseWhenMonthIsLocked(WorkShift shift) {
        LocalDate date = shift.getWorkDate();
        if (date == null || shift.getEmployee() == null) {
            return;
        }
        long locked = payrollRunItemRepository.countLockedForEmployeeAndMonth(
                shift.getEmployee().getId(), date.getYear(), date.getMonthValue());
        if (locked > 0) {
            throw new ConflictException(
                    "Obračun za " + date.getMonthValue() + "/" + date.getYear()
                            + " je zaključan. Otključajte ga pre nego što uklonite smenu.");
        }
    }

    /**
     * The day stops existing for the reports, and the month is told.
     *
     * <p>The daily report is derived from the shift, so a withdrawn shift leaves
     * it with no subject; the monthly report sums daily reports, so removing it
     * is what takes the hours out of the month.
     */
    private void dropDailyReportAndRequeueMonth(WorkShift shift) {
        dailyReportRepository.findByWorkShiftId(shift.getId()).ifPresent(report -> {
            dailyReportCategoryRepository.deleteAllByDailyReportId(report.getId());
            dailyReportRepository.delete(report);
        });
        if (shift.getEmployee() != null && shift.getWorkDate() != null) {
            recalcQueueService.enqueueMonthlyJob(shift.getEmployee(),
                    shift.getWorkDate().getYear(), shift.getWorkDate().getMonthValue(),
                    "WORK_SHIFT_ARCHIVED");
        }
    }

    /** Keeps the reason with the shift, since there is no column for one. */
    private static String appendReason(String note, String reason) {
        if (reason == null || reason.isBlank()) {
            return note;
        }
        String stamp = "Arhivirano: " + reason.trim();
        return note == null || note.isBlank() ? stamp : note + " | " + stamp;
    }
}
