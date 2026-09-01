package com.aleksandarparipovic.marel_app.absence_compensation;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceCategoryCodes;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceOutcome;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecord;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceRecordRepository;
import com.aleksandarparipovic.marel_app.absence_record.AbsenceLogWriter;
import com.aleksandarparipovic.marel_app.absence_record.AbsencePayrollNotice;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecord;
import com.aleksandarparipovic.marel_app.overtime_record.OvertimeRecordRepository;
import com.aleksandarparipovic.marel_app.payroll_run_item.PayrollRunItemRepository;
import com.aleksandarparipovic.marel_app.recalc_queue.RecalcQueueService;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import com.aleksandarparipovic.marel_app.work_shift.repository.WorkShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rebuilds one employee-month's answer to "which overtime paid for which
 * absence, and which days became neradni dan".
 *
 * <p><b>A rebuild, not an edit.</b> The whole month is planned again from the
 * current overtime and the current absences every time, and the result replaces
 * what was there. Updating incrementally would need a dependency graph — an
 * absence added in the middle of the month changes what the bank could afford
 * before AND after it — and that graph has cycles, which the weekend-bonus
 * recheck in {@code DailyRecalcService} already shows how painful they are.
 *
 * <p>Rebuilding also gives the behaviour the factory asked for free: a
 * supervisor who adds two hours to an earlier shift grows the bank, and a
 * no-show that stayed NO because six hours would not buy eight becomes ND on the
 * next pass without anybody going back to it.
 *
 * <p><b>A closed month is never touched.</b> LOCKED is a record of what was paid;
 * APPROVED means payroll is working from the month already. Reallocating either
 * would move a figure somebody is holding, with nothing on screen to say why.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbsenceCompensationAllocator {

    private final OvertimeRecordRepository overtimeRepository;
    private final AbsenceRecordRepository absenceRepository;
    private final AbsenceCompensationRepository compensationRepository;
    private final PayrollRunItemRepository payrollRunItemRepository;
    private final RecalcQueueService recalcQueueService;
    private final AbsenceLogWriter absenceLogWriter;
    private final AbsencePayrollNotice payrollNotice;

    /**
     * @return the shifts whose NO/ND outcome actually moved. Empty means the
     *         month already said what the plan says, and nothing was written or
     *         requeued — which is what stops a recalculation from triggering the
     *         next one indefinitely.
     */
    @Transactional
    public List<Long> allocate(Long employeeId, YearMonth month) {
        if (payrollRunItemRepository.countClosedForEmployeeAndMonth(
                employeeId, month.getYear(), month.getMonthValue()) > 0) {
            log.debug("Allocation skipped for employee {} {}: month is closed", employeeId, month);
            return List.of();
        }

        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        List<OvertimeRecord> bank = overtimeRepository.findForEmployeeBetween(employeeId, from, to);
        List<AbsenceRecord> absences = absenceRepository.findActiveForEmployeeBetween(employeeId, from, to);

        if (absences.isEmpty()) {
            // Nothing to allocate to. Any rows left from a previous plan belong to
            // absences that are gone, and their compensations went with them.
            return List.of();
        }

        AbsenceAllocationPlanner.Plan plan = AbsenceAllocationPlanner.plan(
                bank.stream()
                        .map(o -> new AbsenceAllocationPlanner.BankEntry(
                                o.getId(), o.getWorkDate(), o.getOvertimeMinutes()))
                        .toList(),
                absences.stream().map(this::toPlannerInput).toList());

        rewriteGrants(absences, plan, bank);

        return applyVerdicts(employeeId, absences, plan.verdicts());
    }

    private AbsenceAllocationPlanner.AbsenceInput toPlannerInput(AbsenceRecord absence) {
        WorkShift shift = absence.getWorkShift();
        // Only NO takes part. GO, PLO and SO are paid — there is nothing for the
        // bank to buy back — and sick leave is outside this entirely.
        boolean compensable = AbsenceCategoryCodes.UNPAID_ABSENCE
                .equals(absence.getWorkCodeCategory().getCategoryNo());
        return new AbsenceAllocationPlanner.AbsenceInput(
                absence.getId(),
                shift.getWorkDate(),
                absence.getAbsenceMinutes() == null ? 0 : absence.getAbsenceMinutes(),
                shiftMinutes(shift),
                compensable);
    }

    /**
     * How long the shift is, from its own boundaries.
     *
     * <p>NOT {@code shift.getTotalMinutes()}. That is a generated column mapped
     * {@code insertable/updatable = false} and WITHOUT {@code @Generated}, so
     * Hibernate never reads it back: on a shift created or changed in the same
     * persistence context it is still null. Read from there, every shift measures
     * zero, no absence ever covers its whole shift, and NOTHING becomes ND —
     * silently, because zero is a plausible-looking number.
     *
     * <p>start_at and end_at are NOT NULL and the database's own generated column
     * is defined as exactly this difference, so the two cannot disagree.
     */
    private static int shiftMinutes(WorkShift shift) {
        if (shift.getStartAt() == null || shift.getEndAt() == null) {
            return 0;
        }
        return (int) Duration.between(shift.getStartAt(), shift.getEndAt()).toMinutes();
    }

    /**
     * Replaces the month's compensation rows, but only when the plan differs.
     *
     * <p>The comparison matters more than it looks: the allocation runs on every
     * recalculation that moved the bank, and deleting and re-inserting an
     * identical set each time would churn the table and make "when did this
     * change" unanswerable from the row itself.
     */
    private void rewriteGrants(List<AbsenceRecord> absences,
                               AbsenceAllocationPlanner.Plan plan,
                               List<OvertimeRecord> bank) {
        List<Long> absenceIds = absences.stream().map(AbsenceRecord::getId).toList();
        Set<String> existing = compensationRepository.findForAbsences(absenceIds).stream()
                .map(c -> c.getAbsenceRecord().getId() + ":" + c.getOvertimeRecord().getId()
                        + ":" + c.getCompensatedMinutes())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> wanted = plan.grants().stream()
                .map(g -> g.absenceRecordId() + ":" + g.overtimeRecordId() + ":" + g.minutes())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (existing.equals(wanted)) {
            return;
        }

        compensationRepository.deleteForAbsences(absenceIds);

        Map<Long, OvertimeRecord> bankById = bank.stream()
                .collect(Collectors.toMap(OvertimeRecord::getId, o -> o));
        Map<Long, AbsenceRecord> absenceById = absences.stream()
                .collect(Collectors.toMap(AbsenceRecord::getId, a -> a));

        compensationRepository.saveAll(plan.grants().stream()
                .map(g -> AbsenceCompensation.builder()
                        .absenceRecord(absenceById.get(g.absenceRecordId()))
                        .overtimeRecord(bankById.get(g.overtimeRecordId()))
                        .compensatedMinutes(g.minutes())
                        .build())
                .toList());
    }

    /**
     * Writes each absence's verdict, and puts the ND log in or takes it out where
     * the answer changed.
     *
     * @return the ids of the shifts that changed, already requeued
     */
    private List<Long> applyVerdicts(Long employeeId,
                                     List<AbsenceRecord> absences,
                                     Map<Long, AbsenceAllocationPlanner.AbsenceVerdict> verdicts) {
        List<AbsenceRecord> changed = new ArrayList<>();

        for (AbsenceRecord absence : absences) {
            AbsenceAllocationPlanner.AbsenceVerdict verdict = verdicts.get(absence.getId());
            if (verdict == null) {
                continue;
            }

            AbsenceOutcome before = absence.getOutcome();
            int compensatedBefore = absence.getCompensatedMinutes() == null
                    ? 0 : absence.getCompensatedMinutes();

            absence.setOutcome(verdict.outcome());
            absence.setCompensatedMinutes(verdict.compensatedMinutes());

            /*
             * THE COVERED MINUTES MOVE THE DAY TOO, not only the outcome.
             *
             * Only the payroll charges the UNCOVERED part of an absence, so its
             * category row is absence_minutes minus compensated_minutes. Two
             * hours becoming covered shrinks that row by two hours — and while
             * this only requeued when NO became ND or back, a partial absence
             * that can never be either had its coverage change with nothing
             * rebuilt. The payslip went on charging hours the bank had paid for.
             */
            boolean coverageMoved = compensatedBefore != verdict.compensatedMinutes();
            if (before == verdict.outcome() && !coverageMoved) {
                continue;
            }
            if (before == verdict.outcome()) {
                // Coverage alone: no log to swap, but the day must be rebuilt.
                changed.add(absence);
                continue;
            }

            if (verdict.outcome() == AbsenceOutcome.ND) {
                absence.setNdWorkLog(absenceLogWriter.promoteToNonWorkingDay(absence));
            } else if (before == AbsenceOutcome.ND) {
                // ONLY on the way OUT of ND. An absence reaching NO for the first
                // time has no ND log to take back, and writing a NO log for it
                // here would draw a full day across a shift somebody was only
                // partly away from — the log belongs to full days alone, and the
                // side that recorded one already wrote it.
                absenceLogWriter.demoteToUnpaidAbsence(absence);
            }
            changed.add(absence);
        }

        absenceRepository.saveAll(absences);

        if (changed.isEmpty()) {
            return List.of();
        }
        return requeue(employeeId, changed);
    }

    /**
     * Rebuilds the days whose answer changed.
     *
     * <p>Only the day itself. The NO row on it appears, disappears or changes
     * size — that is what the daily recalculation has to redo — and nothing
     * beyond that day moves.
     *
     * <p><b>No weekend recheck.</b> ND changes what a day is PAID as, not
     * whether anybody turned up: a day bought back with earlier overtime still
     * has no work in it, so its bonus-eligible minutes are zero either way and
     * the week's answer cannot have moved. Enqueuing the weekend here would be
     * work with no effect, under a comment claiming otherwise.
     *
     * <p>No cycle: the daily job recomputes the day's report and its overtime,
     * and the overtime measure drops absence logs, so the bank comes out
     * unchanged and no month is enqueued back.
     */
    private List<Long> requeue(Long employeeId, List<AbsenceRecord> changed) {
        Set<Long> shiftIds = new LinkedHashSet<>();

        for (AbsenceRecord absence : changed) {
            WorkShift shift = absence.getWorkShift();
            shiftIds.add(shift.getId());
            recalcQueueService.enqueueDailyJob(shift, "ND_OUTCOME_CHANGED");
        }

        // NO became ND or the other way round: the day is now priced as a
        // different category, so the payslip built from it is out of date.
        payrollNotice.monthNeedsRepricing(employeeId, changed.get(0).getWorkShift().getWorkDate());

        log.info("Allocation changed {} absence outcome(s) for employee {}",
                changed.size(), employeeId);
        return List.copyOf(shiftIds);
    }
}
