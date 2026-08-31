package com.aleksandarparipovic.marel_app.absence_record;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.work_code.WorkCodeCategory;
import com.aleksandarparipovic.marel_app.work_code.repository.WorkCodeCategoryRepository;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.repository.WorkLogRepository;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The one log a full day off is drawn with, and which of the two it is.
 *
 * <p>A whole shift nobody came in shows on the shift as a single operation
 * spanning it — NO while the day is an unpaid absence, ND once the overtime bank
 * has covered it. There is never more than one, and the two never coexist: the
 * day is one thing or the other, so promoting it swaps the log rather than
 * adding to it.
 *
 * <p><b>Why a log at all.</b> A day nobody worked would otherwise be an empty
 * shift, indistinguishable from one nobody has filled in yet. Drawn this way, the
 * karton says which it is.
 *
 * <p><b>Why it is not measured.</b> {@code DailyRecalcService} drops both
 * categories before it builds anything, so neither is time present, neither
 * earns overtime, and neither drags the day's coefficient. The minutes reach the
 * totals through the absence record these logs mirror.
 *
 * <p><b>Why an operation is needed.</b> {@code work_logs.operation_id} is NOT
 * NULL, and an absence is not work performed on a product. The factory keeps one
 * operation per category for exactly this, found here by the category's CODE
 * rather than by an id written into Java.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbsenceLogWriter {

    private final WorkCodeCategoryRepository categoryRepository;
    private final OperationRepository operationRepository;
    private final WorkLogRepository workLogRepository;

    /**
     * The bank covered the whole shift: the NO log becomes an ND log.
     *
     * @return the ND log, to be linked from the absence record so the reverse
     *         needs no guessing about which row to take back out
     */
    public WorkLog promoteToNonWorkingDay(AbsenceRecord absence) {
        removeLogFor(absence.getWorkShift(), AbsenceCategoryCodes.UNPAID_ABSENCE);
        WorkLog ndLog = write(absence.getWorkShift(), AbsenceCategoryCodes.NON_WORKING_DAY,
                "Neradni dan — pokriveno prekovremenim radom");
        log.info("Shift {} became a neradni dan (absence {})", absence.getWorkShift().getId(), absence.getId());
        return ndLog;
    }

    /**
     * The bank no longer covers it: the ND log goes back to being a NO log.
     *
     * <p>Written back rather than merely deleted. The absence itself has not
     * changed — somebody still did not come in — so the shift must keep saying
     * so, and only which of the two it is has moved.
     */
    public void demoteToUnpaidAbsence(AbsenceRecord absence) {
        clearNonWorkingDayLog(absence);
        write(absence.getWorkShift(), AbsenceCategoryCodes.UNPAID_ABSENCE,
                "Neplaćeno odsustvo — cela smena");
        log.info("Shift {} is an unpaid absence again (absence {})",
                absence.getWorkShift().getId(), absence.getId());
    }

    /**
     * Takes away whichever log stands for this absence, and writes nothing back.
     *
     * <p>For a withdrawn absence: the day stops asserting anything, because
     * nobody is claiming any more that the employee was away.
     */
    public void removeAll(AbsenceRecord absence) {
        clearNonWorkingDayLog(absence);
        removeLogFor(absence.getWorkShift(), AbsenceCategoryCodes.UNPAID_ABSENCE);
    }

    /**
     * Ensures the shift carries a NO log spanning it, and hands it back.
     *
     * <p>Idempotent: called whenever a full-shift absence is recorded, from
     * whichever side recorded it.
     */
    public WorkLog ensureUnpaidAbsenceLog(WorkShift shift) {
        return findLog(shift, AbsenceCategoryCodes.UNPAID_ABSENCE)
                .orElseGet(() -> write(shift, AbsenceCategoryCodes.UNPAID_ABSENCE,
                        "Neplaćeno odsustvo — cela smena"));
    }

    public Optional<WorkLog> findLog(WorkShift shift, String categoryNo) {
        return workLogRepository.findActiveLogsWithRefsForShift(shift.getId()).stream()
                .filter(wl -> wl.getWorkCode() != null
                        && categoryNo.equals(wl.getWorkCode().getCategoryNo()))
                .findFirst();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void clearNonWorkingDayLog(AbsenceRecord absence) {
        WorkLog linked = absence.getNdWorkLog();
        absence.setNdWorkLog(null);
        if (linked != null) {
            workLogRepository.delete(linked);
        } else {
            // The link is the fast path, not the only one: a log written before
            // the link existed, or by an earlier pass, still has to go.
            removeLogFor(absence.getWorkShift(), AbsenceCategoryCodes.NON_WORKING_DAY);
        }
    }

    private void removeLogFor(WorkShift shift, String categoryNo) {
        findLog(shift, categoryNo).ifPresent(workLogRepository::delete);
    }

    /**
     * Spans {@code start_at} to {@code end_at} of the shift itself, so the day's
     * covered minutes would come out as exactly the shift's length — which is
     * also exactly what the recalculation subtracts again when it drops these.
     */
    private WorkLog write(WorkShift shift, String categoryNo, String note) {
        LocalDate workDate = shift.getWorkDate();

        WorkCodeCategory category = categoryRepository
                .findInForceByCategoryNo(categoryNo, workDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Kategorija " + categoryNo + " ne postoji za " + workDate + "."));

        WorkLog saved = workLogRepository.save(WorkLog.builder()
                .workShift(shift)
                .operation(singleOperationFor(categoryNo))
                .workCode(category)
                .startAt(shift.getStartAt())
                .endAt(shift.getEndAt())
                .quantity(0)
                .scrap(0)
                .isActive(true)
                .normMultiplierSnapshot(BigDecimal.valueOf(
                        category.getNormMultiplier() == null ? 0d : category.getNormMultiplier()))
                .note(note)
                .build());
        log.debug("{} log {} written across shift {}", categoryNo, saved.getId(), shift.getId());
        return saved;
    }

    /**
     * Missing configuration is an error, not a shrug. Quietly leaving a day
     * undrawn because the operation had been archived would take away a weekend
     * bonus with nothing on screen to explain it.
     */
    private Operation singleOperationFor(String categoryNo) {
        List<Operation> candidates = operationRepository.findActiveByWorkCodeCategoryNo(categoryNo);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Nema aktivne operacije sa kategorijom " + categoryNo
                            + ". Odsustvo se ne može prikazati u smeni bez nje.");
        }
        if (candidates.size() > 1) {
            // Nothing in the database stops a second one being created, and
            // picking silently would make which log gets written depend on
            // insertion order.
            throw new IllegalStateException(
                    "Očekivana je tačno jedna operacija sa kategorijom " + categoryNo
                            + ", pronađeno " + candidates.size() + ".");
        }
        return candidates.get(0);
    }
}
