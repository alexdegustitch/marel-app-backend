package com.aleksandarparipovic.marel_app.absence_record;

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
 * Keeps a full day off saying the same thing in both places it is written.
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
public class FullDayAbsenceSync {

    private final AbsenceRecordRepository absenceRepository;
    private final WorkLogRepository workLogRepository;
    private final AbsenceLogWriter absenceLogWriter;
    private final CurrentUserService currentUserService;

    /**
     * Makes the shift's absence record agree with its absence log, or refuses the
     * batch when the two could not agree.
     */
    public void syncForShift(WorkShift shift) {
        List<WorkLog> logs = workLogRepository.findActiveLogsWithRefsForShift(shift.getId());

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
        mirroredAbsence(shift).ifPresent(absence -> {
            absence.setIsActive(false);
            absence.setOutcome(null);
            absence.setCompensatedMinutes(0);
            absenceRepository.save(absence);
            log.info("Full-day absence {} withdrawn with its NO log on shift {}",
                    absence.getId(), shift.getId());
        });
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
