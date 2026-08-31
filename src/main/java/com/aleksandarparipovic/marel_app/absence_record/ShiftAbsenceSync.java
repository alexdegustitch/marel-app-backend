package com.aleksandarparipovic.marel_app.absence_record;

import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceCompensationRepository;
import com.aleksandarparipovic.marel_app.auth.CurrentUserService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reconciles a shift's absences with what its work logs now say.
 *
 * <p>A whole shift nobody came in is entered as a NO work log, because that is
 * where a supervisor already is when they find out. But the log is only how it is
 * DRAWN — what decides whether the overtime bank can buy the day back, and
 * whether that week's weekend bonus survives, is the absence record. So the log
 * is mirrored into one, and the two are kept in step from here.
 *
 * <p>Called after every work-log batch that touched a shift, so it also handles
 * the removal: take the NO log away and the absence goes with it.
 *
 * <p><b>A NO log is all-or-nothing.</b> It must span the whole shift and be the
 * only thing on it. Part of a shift is not a day nobody came in — it is a gap,
 * and gaps are recorded through the absence dialog, which is the only place that
 * can say from when to when.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShiftAbsenceSync {

    private final AbsenceRecordRepository absenceRepository;
    private final AbsenceCompensationRepository compensationRepository;
    private final WorkLogRepository workLogRepository;
    private final AbsenceLogWriter absenceLogWriter;
    private final CurrentUserService currentUserService;

    /**
     * Makes the shift's absence record agree with its absence log, or refuses the
     * batch when the two could not agree.
     */
    public void syncForShift(WorkShift shift) {
        List<WorkLog> logs = workLogRepository.findActiveLogsWithRefsForShift(shift.getId());

        withdrawAbsencesCoveredByWork(shift, logs);

        List<WorkLog> unpaidLogs = logs.stream()
                .filter(wl -> wl.getWorkCode() != null
                        && AbsenceCategoryCodes.UNPAID_ABSENCE.equals(wl.getWorkCode().getCategoryNo()))
                .toList();
        boolean hasNonWorkingDayLog = logs.stream()
                .anyMatch(wl -> wl.getWorkCode() != null
                        && AbsenceCategoryCodes.NON_WORKING_DAY.equals(wl.getWorkCode().getCategoryNo()));

        if (unpaidLogs.isEmpty()) {
            if (!hasNonWorkingDayLog) {
                // Nothing on the shift says anybody was away any more. An ND log,
                // though, means the allocation owns this day and put it there —
                // leaving it alone is what stops a work-log edit elsewhere on the
                // day from quietly revoking a neradni dan.
                withdrawMirroredAbsence(shift);
            }
            return;
        }

        if (unpaidLogs.size() > 1) {
            throw new ConflictException(
                    "Smena može imati najviše jedno neplaćeno odsustvo (NO).");
        }

        WorkLog unpaid = unpaidLogs.get(0);
        requireSpansWholeShift(shift, unpaid);
        requireNothingElseOnTheShift(logs);

        mirrorIntoAbsenceRecord(shift, unpaid);
        withdrawAbsencesSubsumedByTheWholeDay(shift);
    }

    /**
     * Work recorded over an absence withdraws it.
     *
     * <p>Entering work where somebody was marked away is a CORRECTION — "he did
     * come in after all" — not a conflict to refuse. The two cannot both stand:
     * the shift would claim the same minutes as worked and as absent, and the
     * overtime measure reads covered minutes, so it would count them twice over.
     *
     * <p>The absence goes whole, even when the work covers only part of it.
     * Trimming it to what is left would be the application deciding where the
     * remaining absence now begins, which is exactly the thing only the person
     * entering it knows.
     *
     * <p>NO and ND logs are not "work" here. They ARE absences, drawn on the
     * shift, and the one they mirror is the row this must never take away.
     */
    private void withdrawAbsencesCoveredByWork(WorkShift shift, List<WorkLog> logs) {
        List<WorkLog> workLogs = logs.stream()
                .filter(wl -> wl.getWorkCode() == null
                        || !AbsenceCategoryCodes.isAbsenceLog(wl.getWorkCode().getCategoryNo()))
                .toList();
        if (workLogs.isEmpty()) {
            return;
        }

        for (AbsenceRecord absence : absenceRepository.findActiveForShift(shift.getId())) {
            boolean coveredByWork = workLogs.stream()
                    .anyMatch(wl -> overlaps(absence.getStartAt(), absence.getEndAt(),
                            wl.getStartAt(), wl.getEndAt()));
            if (coveredByWork) {
                withdraw(absence, "work was recorded over it");
            }
        }
    }

    /**
     * A whole day off supersedes anything recorded for part of it.
     *
     * <p>The full-shift absence the NO log mirrors already covers those minutes,
     * and leaving the shorter one beside it would count them twice.
     */
    private void withdrawAbsencesSubsumedByTheWholeDay(WorkShift shift) {
        Optional<AbsenceRecord> wholeDay = mirroredAbsence(shift);
        if (wholeDay.isEmpty()) {
            return;
        }
        Long keep = wholeDay.get().getId();
        for (AbsenceRecord absence : absenceRepository.findActiveForShift(shift.getId())) {
            if (!absence.getId().equals(keep)) {
                withdraw(absence, "the whole shift is now a full day off");
            }
        }
    }

    private static boolean overlaps(OffsetDateTime aStart, OffsetDateTime aEnd,
                                    OffsetDateTime bStart, OffsetDateTime bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    // ── Guards ───────────────────────────────────────────────────────────────

    private void requireSpansWholeShift(WorkShift shift, WorkLog unpaid) {
        if (shift.getStartAt() == null || shift.getEndAt() == null) {
            throw new ConflictException("Smena nema definisano trajanje.");
        }
        boolean spans = !unpaid.getStartAt().isAfter(shift.getStartAt())
                && !unpaid.getEndAt().isBefore(shift.getEndAt());
        if (!spans) {
            throw new ConflictException(
                    "Neplaćeno odsustvo (NO) kao operacija mora pokrivati celu smenu."
                            + " Za deo smene koristite dugme \"Odsustva\" na toj smeni.");
        }
    }

    private void requireNothingElseOnTheShift(List<WorkLog> logs) {
        if (logs.size() > 1) {
            throw new ConflictException(
                    "Neplaćeno odsustvo (NO) za celu smenu ne može stajati uz uneti rad."
                            + " Uklonite ostale operacije ili unesite odsustvo za deo smene"
                            + " preko dugmeta \"Odsustva\".");
        }
    }

    // ── The mirror ───────────────────────────────────────────────────────────

    private void mirrorIntoAbsenceRecord(WorkShift shift, WorkLog unpaid) {
        OffsetDateTime start = unpaid.getStartAt();
        OffsetDateTime end = unpaid.getEndAt();
        int minutes = (int) Duration.between(start, end).toMinutes();

        Optional<AbsenceRecord> existing = mirroredAbsence(shift);
        if (existing.isPresent()) {
            AbsenceRecord absence = existing.get();
            if (start.isEqual(absence.getStartAt()) && end.isEqual(absence.getEndAt())) {
                return;
            }
            absence.setStartAt(start);
            absence.setEndAt(end);
            absence.setAbsenceMinutes(minutes);
            absenceRepository.save(absence);
            return;
        }

        absenceRepository.save(AbsenceRecord.builder()
                .employee(shift.getEmployee())
                .workShift(shift)
                .workCodeCategory(unpaid.getWorkCode())
                .startAt(start)
                .endAt(end)
                .absenceMinutes(minutes)
                .normMultiplierSnapshot(BigDecimal.valueOf(
                        unpaid.getWorkCode().getNormMultiplier() == null
                                ? 0d : unpaid.getWorkCode().getNormMultiplier()))
                .paidMinutes(0)
                .compensatedMinutes(0)
                .createdBy(currentUserService.getCurrentUserId())
                .isActive(true)
                .build());
        log.info("Full-day absence mirrored from NO log {} on shift {}", unpaid.getId(), shift.getId());
    }

    /**
     * Archived rather than deleted, as a withdrawn absence always is: it is what
     * somebody entered, and that week's weekend bonus may have been decided by it.
     */
    private void withdrawMirroredAbsence(WorkShift shift) {
        mirroredAbsence(shift).ifPresent(absence -> withdraw(absence, "its NO log was removed"));
    }

    /**
     * Takes an absence out of the reckoning, and its compensations with it.
     *
     * <p><b>The compensations are the part that is easy to forget.</b> The
     * foreign key cascades on DELETE, and this is an ARCHIVE — so without this
     * the rows survive, each one still claiming that some overtime day paid for
     * an absence nobody is claiming any more. The allocation only ever rewrites
     * the rows of absences it can still see, so nothing would clear them later.
     *
     * <p>Archived rather than deleted, as every withdrawal here is: it is what
     * somebody entered, and that week's weekend bonus may have been decided by
     * it. The audit trigger on absence_records keeps the trail either way.
     */
    private void withdraw(AbsenceRecord absence, String reason) {
        compensationRepository.deleteForAbsences(List.of(absence.getId()));

        absence.setIsActive(false);
        absence.setOutcome(null);
        absence.setCompensatedMinutes(0);
        absenceRepository.save(absence);
        log.info("Absence {} on shift {} withdrawn: {}",
                absence.getId(), absence.getWorkShift().getId(), reason);
    }

    /**
     * The full-shift absence, if the shift has one.
     *
     * <p>Identified by SPAN rather than by a flag: a full-shift absence is the
     * only kind the log mirrors, and one entered through the dialog for part of
     * the shift is nothing this class may touch.
     */
    private Optional<AbsenceRecord> mirroredAbsence(WorkShift shift) {
        return absenceRepository.findActiveForShift(shift.getId()).stream()
                .filter(a -> a.getStartAt() != null && a.getEndAt() != null)
                .filter(a -> !a.getStartAt().isAfter(shift.getStartAt())
                        && !a.getEndAt().isBefore(shift.getEndAt()))
                .findFirst();
    }
}
