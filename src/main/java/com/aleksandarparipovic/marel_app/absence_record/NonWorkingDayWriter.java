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

/**
 * Puts the single ND log across a shift the overtime bank paid for, and takes it
 * back out when the bank no longer does.
 *
 * <p>A neradni dan is shown as work-shaped, so it appears on the shift beside
 * everything else rather than as a hole somebody has to know how to read. The
 * category is ND — unpaid, coefficient zero — so it moves no wage.
 *
 * <p><b>Why an operation at all.</b> {@code work_logs.operation_id} is NOT NULL,
 * and ND is not work performed on a product. The factory keeps one operation
 * carrying the ND category for exactly this, and this class finds it by the
 * category's CODE rather than by an id written into Java.
 *
 * <p>Missing configuration is an error, not a shrug. Quietly leaving the day as
 * NO because the ND operation had been archived would take away a weekend bonus
 * with nothing on screen to explain it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NonWorkingDayWriter {

    private final WorkCodeCategoryRepository categoryRepository;
    private final OperationRepository operationRepository;
    private final WorkLogRepository workLogRepository;

    /**
     * Writes the ND log across the whole shift and hands it back to be linked.
     *
     * <p>Spans {@code start_at} to {@code end_at} of the shift itself, so the
     * day's covered minutes come out as the shift's length exactly — and so
     * {@code OvertimeQueryRepository} can subtract precisely those minutes again
     * when it measures overtime.
     */
    public WorkLog write(AbsenceRecord absence) {
        WorkShift shift = absence.getWorkShift();
        LocalDate workDate = shift.getWorkDate();

        WorkCodeCategory ndCategory = categoryRepository
                .findInForceByCategoryNo(AbsenceCategoryCodes.NON_WORKING_DAY, workDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Kategorija ND ne postoji za " + workDate
                                + ". Neradni dan se ne može upisati bez nje."));

        Operation ndOperation = singleOperationFor(AbsenceCategoryCodes.NON_WORKING_DAY);

        WorkLog ndLog = WorkLog.builder()
                .workShift(shift)
                .operation(ndOperation)
                .workCode(ndCategory)
                .startAt(shift.getStartAt())
                .endAt(shift.getEndAt())
                .quantity(0)
                .scrap(0)
                .isActive(true)
                .normMultiplierSnapshot(BigDecimal.valueOf(ndCategory.getNormMultiplier()))
                .note("Neradni dan — pokriveno prekovremenim radom")
                .build();

        WorkLog saved = workLogRepository.save(ndLog);
        log.info("ND log {} written across shift {} for absence {}",
                saved.getId(), shift.getId(), absence.getId());
        return saved;
    }

    /**
     * Takes the ND log back out when the day stops being covered.
     *
     * <p>A hard delete: the log asserts the employee was not expected to work
     * that day, and once the bank no longer pays for it that assertion is simply
     * untrue. Leaving it archived would keep it in nothing and confuse the shift
     * it still hangs from. What was entered — the absence itself — is untouched.
     */
    public void remove(AbsenceRecord absence) {
        WorkLog existing = absence.getNdWorkLog();
        if (existing == null) {
            return;
        }
        absence.setNdWorkLog(null);
        workLogRepository.delete(existing);
        log.info("ND log {} removed: absence {} is no longer covered", existing.getId(), absence.getId());
    }

    private Operation singleOperationFor(String categoryNo) {
        List<Operation> candidates = operationRepository.findActiveByWorkCodeCategoryNo(categoryNo);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Nema aktivne operacije sa kategorijom " + categoryNo
                            + ". Neradni dan se ne može upisati bez nje.");
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
