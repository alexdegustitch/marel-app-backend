package com.aleksandarparipovic.marel_app.absence_record;

import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceCompensationRepository;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceCategoryDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceCreateRequest;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.AbsenceRecordDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.CompensationSourceDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.OvertimeBankDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.OvertimeDayDto;
import com.aleksandarparipovic.marel_app.absence_record.dto.AbsenceDtos.SuggestedAbsenceDto;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecord;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecordRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.interval.WorkIntervalCalculator;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recording time inside a shift that nobody was there for.
 *
 * <p>What becomes of an absence is NOT decided here. This class writes what
 * somebody entered and asks for the day to be recalculated; whether the overtime
 * bank covers it, and whether the day therefore becomes a neradni dan, is
 * {@code AbsenceCompensationAllocator}'s answer and is rewritten on every pass.
 * Nothing here sets {@code outcome} or {@code compensatedMinutes}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbsenceRecordService {

    private static final String TYPE_ABSENCE = "ABSENCE";

    private final AbsenceRecordRepository repository;
    private final AbsenceCompensationRepository compensationRepository;
    private final OvertimeRecordRepository overtimeRepository;
    private final WorkShiftRepository workShiftRepository;
    private final WorkLogRepository workLogRepository;
    private final WorkCodeCategoryRepository categoryRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final RecalcQueueService recalcQueueService;
    private final NonWorkingDayWriter nonWorkingDayWriter;
    private final WorkIntervalCalculator intervalCalculator;
    private final CurrentUserService currentUserService;

    // ── Reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AbsenceRecordDto> forShift(Long workShiftId) {
        List<AbsenceRecord> absences = repository.findActiveForShift(workShiftId);
        if (absences.isEmpty()) {
            return List.of();
        }

        Map<Long, List<CompensationSourceDto>> sources = compensationRepository
                .findForAbsences(absences.stream().map(AbsenceRecord::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        c -> c.getAbsenceRecord().getId(),
                        Collectors.mapping(
                                c -> new CompensationSourceDto(
                                        c.getOvertimeRecord().getWorkDate(), c.getCompensatedMinutes()),
                                Collectors.toList())));

        return absences.stream()
                .map(a -> toDto(a, sources.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    /**
     * The stretches of the shift no work log covers.
     *
     * <p>Computed rather than stored, and offered rather than applied: a gap is
     * evidence, not a decision. The person looking at it knows whether it was an
     * absence, a break or an entry somebody forgot.
     *
     * <p>Gaps already claimed by an absence are not offered again.
     */
    @Transactional(readOnly = true)
    public List<SuggestedAbsenceDto> suggestionsForShift(Long workShiftId) {
        WorkShift shift = requireShift(workShiftId);
        if (shift.getStartAt() == null || shift.getEndAt() == null) {
            return List.of();
        }

        List<WorkIntervalCalculator.Range> covered = new ArrayList<>();

        for (WorkLog wl : workLogRepository.findActiveLogsWithRefsForShift(workShiftId)) {
            covered.add(new WorkIntervalCalculator.Range(wl.getStartAt(), wl.getEndAt()));
        }
        for (AbsenceRecord absence : repository.findActiveForShift(workShiftId)) {
            if (absence.getStartAt() != null && absence.getEndAt() != null) {
                covered.add(new WorkIntervalCalculator.Range(absence.getStartAt(), absence.getEndAt()));
            }
        }

        return gaps(shift.getStartAt(), shift.getEndAt(), covered).stream()
                .map(r -> new SuggestedAbsenceDto(r.start(), r.end(), (int) r.minutes()))
                // A stray minute between two logs is rounding, not an absence.
                .filter(s -> s.minutes() > 0)
                .toList();
    }

    /**
     * What this screen may record: neplaćeno odsustvo, and nothing else.
     *
     * <p>NO ONLY, deliberately. This feature is about the absence the overtime
     * bank can buy back and the weekend bonus it decides. Godišnji odmor, plaćeno
     * odsustvo and službeno odsutan are paid, take no part in any of it, and are
     * not this screen's business — offering them here would quietly become a new
     * way to record them, with new totals behind it.
     *
     * <p>ND is not offered either, and for a different reason: it is written by
     * the application when the bank covers a whole shift, never chosen.
     *
     * <p>Returned as a list rather than a single value so the shape survives the
     * day somebody decides a second kind belongs here.
     */
    @Transactional(readOnly = true)
    public List<AbsenceCategoryDto> selectableCategories(LocalDate workDate) {
        return categoryRepository
                .findInForceByCategoryNo(AbsenceCategoryCodes.UNPAID_ABSENCE, workDate)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .filter(c -> TYPE_ABSENCE.equalsIgnoreCase(c.getType()))
                .map(c -> List.of(new AbsenceCategoryDto(c.getId(), c.getCategoryNo(),
                        c.getCategoryName(), Boolean.TRUE.equals(c.getIsPaid()))))
                .orElseGet(List::of);
    }

    /**
     * The bank of the month this shift falls in, for the employee who worked it.
     *
     * <p>Addressed by SHIFT rather than by employee-and-month because that is
     * what the caller has: the absence dialog opens from a shift card, and
     * deriving the other two here saves widening the shift DTO with an
     * employee id the card never otherwise needs.
     */
    @Transactional(readOnly = true)
    public OvertimeBankDto bankForShift(Long workShiftId) {
        WorkShift shift = requireShift(workShiftId);
        LocalDate date = shift.getWorkDate();
        return bankFor(shift.getEmployee().getId(), date.getYear(), date.getMonthValue());
    }

    @Transactional(readOnly = true)
    public OvertimeBankDto bankFor(Long employeeId, int year, int month) {
        YearMonth period = YearMonth.of(year, month);
        List<OvertimeRecord> days = overtimeRepository
                .findForEmployeeBetween(employeeId, period.atDay(1), period.atEndOfMonth());

        Map<Long, Integer> spentByDay = compensationRepository
                .findForAbsences(repository
                        .findActiveForEmployeeBetween(employeeId, period.atDay(1), period.atEndOfMonth())
                        .stream().map(AbsenceRecord::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        c -> c.getOvertimeRecord().getId(),
                        Collectors.summingInt(c -> c.getCompensatedMinutes())));

        List<OvertimeDayDto> dayDtos = days.stream()
                .map(o -> new OvertimeDayDto(o.getWorkDate(), o.getOvertimeMinutes(),
                        spentByDay.getOrDefault(o.getId(), 0)))
                .toList();

        int earned = dayDtos.stream().mapToInt(OvertimeDayDto::overtimeMinutes).sum();
        int spent = dayDtos.stream().mapToInt(OvertimeDayDto::spentMinutes).sum();
        return new OvertimeBankDto(earned, spent, earned - spent, dayDtos);
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    @Transactional
    public AbsenceRecordDto create(AbsenceCreateRequest request) {
        WorkShift shift = requireShift(request.workShiftId());
        refuseWhenMonthIsClosed(shift);

        WorkCodeCategory category = categoryRepository.findById(request.workCodeCategoryId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kategorija ne postoji: " + request.workCodeCategoryId()));
        requireAbsenceCategory(category);

        OffsetDateTime start = request.startAt();
        OffsetDateTime end = request.endAt();
        validateWithinShift(shift, start, end);
        refuseOverlaps(shift, start, end);

        int minutes = (int) Duration.between(start, end).toMinutes();

        AbsenceRecord absence = repository.save(AbsenceRecord.builder()
                .employee(shift.getEmployee())
                .workShift(shift)
                .workCodeCategory(category)
                .startAt(start)
                .endAt(end)
                .absenceMinutes(minutes)
                .normMultiplierSnapshot(BigDecimal.valueOf(
                        category.getNormMultiplier() == null ? 0d : category.getNormMultiplier()))
                .paidMinutes(0)
                .compensatedMinutes(0)
                .note(request.note())
                .createdBy(currentUserService.getCurrentUserId())
                .isActive(true)
                .build());

        // The day is rebuilt, which moves the absence totals; that in turn
        // enqueues the month, and the month is where the bank is allocated.
        recalcQueueService.enqueueDailyJob(shift, "ABSENCE_RECORDED");
        log.info("Absence {} recorded on shift {} ({} min)", absence.getId(), shift.getId(), minutes);

        return toDto(absence, List.of());
    }

    /**
     * Withdraws an absence.
     *
     * <p>Archived rather than deleted: it is what somebody entered, and the
     * weekend bonus of that week may have been decided by it. Its ND log, if it
     * had one, goes — that log asserts a day nobody has to work, and without the
     * absence behind it the assertion has nothing to stand on.
     */
    @Transactional
    public void archive(Long id) {
        AbsenceRecord absence = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Odsustvo ne postoji: " + id));
        if (Boolean.FALSE.equals(absence.getIsActive())) {
            return;
        }
        WorkShift shift = absence.getWorkShift();
        refuseWhenMonthIsClosed(shift);

        nonWorkingDayWriter.remove(absence);
        absence.setIsActive(false);
        absence.setOutcome(null);
        absence.setCompensatedMinutes(0);
        repository.save(absence);

        recalcQueueService.enqueueDailyJob(shift, "ABSENCE_WITHDRAWN");
        log.info("Absence {} withdrawn from shift {}", id, shift.getId());
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private WorkShift requireShift(Long workShiftId) {
        return workShiftRepository.findById(workShiftId)
                .orElseThrow(() -> new EntityNotFoundException("Smena ne postoji: " + workShiftId));
    }

    private void requireAbsenceCategory(WorkCodeCategory category) {
        if (!TYPE_ABSENCE.equalsIgnoreCase(category.getType())) {
            throw new ConflictException(
                    "Kategorija \"" + category.getCategoryNo() + "\" nije odsustvo.");
        }
        if (AbsenceCategoryCodes.NON_WORKING_DAY.equals(category.getCategoryNo())) {
            throw new ConflictException(
                    "Neradni dan (ND) se ne unosi ručno. Upisuje se sam kada"
                            + " prekovremeni rad pokrije celu smenu.");
        }
    }

    private void validateWithinShift(WorkShift shift, OffsetDateTime start, OffsetDateTime end) {
        if (!end.isAfter(start)) {
            throw new ConflictException("Kraj odsustva mora biti posle početka.");
        }
        if (shift.getStartAt() == null || shift.getEndAt() == null) {
            throw new ConflictException("Smena nema definisano trajanje.");
        }
        if (start.isBefore(shift.getStartAt()) || end.isAfter(shift.getEndAt())) {
            throw new ConflictException("Odsustvo mora biti unutar smene.");
        }
    }

    /**
     * An absence cannot sit on top of recorded work, or of another absence.
     *
     * <p>Both would be the shift saying two things about one minute — and the
     * overtime measure reads covered minutes, so an absence overlapping work
     * would be counted as time present and absent at once.
     */
    private void refuseOverlaps(WorkShift shift, OffsetDateTime start, OffsetDateTime end) {
        for (WorkLog wl : workLogRepository.findActiveLogsWithRefsForShift(shift.getId())) {
            if (overlaps(start, end, wl.getStartAt(), wl.getEndAt())) {
                throw new ConflictException(
                        "Odsustvo se preklapa sa unetim radom u toj smeni.");
            }
        }
        for (AbsenceRecord other : repository.findActiveForShift(shift.getId())) {
            if (overlaps(start, end, other.getStartAt(), other.getEndAt())) {
                throw new ConflictException("Odsustvo se preklapa sa već unetim odsustvom.");
            }
        }
    }

    private void refuseWhenMonthIsClosed(WorkShift shift) {
        LocalDate date = shift.getWorkDate();
        if (date == null || shift.getEmployee() == null) {
            return;
        }
        if (payrollRunItemRepository.countClosedForEmployeeAndMonth(
                shift.getEmployee().getId(), date.getYear(), date.getMonthValue()) > 0) {
            throw new ConflictException(
                    "Obračun za " + date.getMonthValue() + "/" + date.getYear()
                            + " je predat ili zaključan. Vratite ga na doradu pre izmene odsustava.");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean overlaps(OffsetDateTime aStart, OffsetDateTime aEnd,
                                    OffsetDateTime bStart, OffsetDateTime bEnd) {
        if (bStart == null || bEnd == null) {
            return false;
        }
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /** What is left of [start, end] once every covered range is taken out of it. */
    private List<WorkIntervalCalculator.Range> gaps(OffsetDateTime start,
                                                    OffsetDateTime end,
                                                    List<WorkIntervalCalculator.Range> covered) {
        List<WorkIntervalCalculator.Range> merged = covered.stream()
                .filter(r -> r.start() != null && r.end() != null)
                .filter(r -> r.end().isAfter(start) && r.start().isBefore(end))
                .sorted((a, b) -> a.start().compareTo(b.start()))
                .collect(Collectors.collectingAndThen(Collectors.toList(), AbsenceRecordService::merge));

        List<WorkIntervalCalculator.Range> result = new ArrayList<>();
        OffsetDateTime cursor = start;
        for (WorkIntervalCalculator.Range range : merged) {
            if (range.start().isAfter(cursor)) {
                result.add(new WorkIntervalCalculator.Range(cursor, range.start()));
            }
            if (range.end().isAfter(cursor)) {
                cursor = range.end();
            }
        }
        if (cursor.isBefore(end)) {
            result.add(new WorkIntervalCalculator.Range(cursor, end));
        }
        return result;
    }

    private static List<WorkIntervalCalculator.Range> merge(List<WorkIntervalCalculator.Range> sorted) {
        List<WorkIntervalCalculator.Range> merged = new ArrayList<>();
        for (WorkIntervalCalculator.Range range : sorted) {
            if (merged.isEmpty()) {
                merged.add(range);
                continue;
            }
            WorkIntervalCalculator.Range last = merged.get(merged.size() - 1);
            if (!range.start().isAfter(last.end())) {
                merged.set(merged.size() - 1, new WorkIntervalCalculator.Range(
                        last.start(), last.end().isAfter(range.end()) ? last.end() : range.end()));
            } else {
                merged.add(range);
            }
        }
        return merged;
    }

    private AbsenceRecordDto toDto(AbsenceRecord absence, List<CompensationSourceDto> sources) {
        WorkCodeCategory category = absence.getWorkCodeCategory();
        return new AbsenceRecordDto(
                absence.getId(),
                absence.getWorkShift().getId(),
                category.getId(),
                category.getCategoryNo(),
                category.getCategoryName(),
                absence.getStartAt(),
                absence.getEndAt(),
                absence.getAbsenceMinutes() == null ? 0 : absence.getAbsenceMinutes(),
                absence.getCompensatedMinutes() == null ? 0 : absence.getCompensatedMinutes(),
                absence.getOutcome() == null ? null : absence.getOutcome().name(),
                absence.getNote(),
                sources);
    }
}
