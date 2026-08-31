package com.aleksandarparipovic.marel_app.absence_compensation;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceOutcome;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which overtime paid for which absence, and what each absence became.
 *
 * <p>No database, no entities, no clock: the same inputs give the same plan
 * every time. That is not tidiness — the allocation runs again on every
 * recalculation, and a plan that varied would rewrite an unchanged month and
 * re-trigger everything downstream of it.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li><b>Chronological.</b> Absences are covered in the order they happened.
 *       An absence early in the month spends the bank before a later one sees
 *       it, even when the later one would have made better use of it.</li>
 *   <li><b>Oldest overtime first.</b> Within one absence, the bank is spent
 *       FIFO, so the record reads "two hours from the 12th, one from the 10th"
 *       rather than naming whichever day happened to be loaded first.</li>
 *   <li><b>ND needs the WHOLE shift.</b> An absence becomes a neradni dan only
 *       when it covers its entire shift AND the bank covered all of it. Anything
 *       less stays NO, however much of it was compensated.</li>
 *   <li><b>One month, no carry-over.</b> The caller passes one month's overtime
 *       and one month's absences; nothing here reaches past either end.</li>
 * </ol>
 *
 * <p>A partly covered absence keeps {@link AbsenceOutcome#NO} and still spoils
 * that week's weekend bonus. Compensation never makes absent time PAID — it buys
 * the day's standing, not its wage.
 */
public final class AbsenceAllocationPlanner {

    private AbsenceAllocationPlanner() {
    }

    /**
     * One day's overtime, as much of it as is still unspent.
     *
     * @param overtimeRecordId the row this came from, carried so the plan can name it
     * @param workDate         the day worked long, used only for FIFO ordering
     * @param minutes          the whole day's overtime, before this plan spends any
     */
    public record BankEntry(Long overtimeRecordId, LocalDate workDate, int minutes) {
    }

    /**
     * One absence, as the planner needs to see it.
     *
     * @param absenceRecordId the row this came from
     * @param workDate        the shift's date, used only for ordering
     * @param absenceMinutes  how long the employee was away
     * @param shiftMinutes    how long the shift was — what a full day costs
     * @param compensable     false for paid absences (GO, PLO, SO) and for sick
     *                        leave, which take no part in the bank at all
     */
    public record AbsenceInput(Long absenceRecordId,
                               LocalDate workDate,
                               int absenceMinutes,
                               int shiftMinutes,
                               boolean compensable) {
    }

    /** This many minutes of that overtime day paid for this absence. */
    public record Grant(Long absenceRecordId, Long overtimeRecordId, int minutes) {
    }

    /** What one absence ended up as. {@code outcome} is null for a paid absence. */
    public record AbsenceVerdict(Long absenceRecordId, AbsenceOutcome outcome, int compensatedMinutes) {
    }

    /**
     * @param grants   what to write into absence_compensations, in a stable order
     * @param verdicts one per absence given, keyed by absence id, insertion-ordered
     */
    public record Plan(List<Grant> grants, Map<Long, AbsenceVerdict> verdicts) {
    }

    /**
     * @param bank     one month's overtime, OLDEST FIRST — the caller's ordering is
     *                 trusted and is what FIFO means here
     * @param absences one month's absences, CHRONOLOGICAL — likewise
     */
    public static Plan plan(List<BankEntry> bank, List<AbsenceInput> absences) {
        int[] remaining = new int[bank.size()];
        for (int i = 0; i < bank.size(); i++) {
            remaining[i] = bank.get(i).minutes();
        }

        List<Grant> grants = new ArrayList<>();
        Map<Long, AbsenceVerdict> verdicts = new LinkedHashMap<>();

        for (AbsenceInput absence : absences) {
            if (!absence.compensable()) {
                // A paid absence is not a thing the bank can buy back. It gets no
                // outcome at all rather than NO, so that "NO" keeps meaning the one
                // thing it means: unpaid, uncovered, and spoiling the weekend bonus.
                verdicts.put(absence.absenceRecordId(), new AbsenceVerdict(absence.absenceRecordId(), null, 0));
                continue;
            }

            int outstanding = absence.absenceMinutes();
            for (int i = 0; i < bank.size() && outstanding > 0; i++) {
                if (remaining[i] <= 0) {
                    continue;
                }
                int take = Math.min(outstanding, remaining[i]);
                remaining[i] -= take;
                outstanding -= take;
                grants.add(new Grant(absence.absenceRecordId(), bank.get(i).overtimeRecordId(), take));
            }

            int compensated = absence.absenceMinutes() - outstanding;

            // Covering the whole shift is what makes a day a NON-working day. A
            // shift of ten hours therefore costs ten, not eight: the day is only
            // one the employee was never expected to work if none of it is left
            // standing as absence.
            boolean coversWholeShift = absence.shiftMinutes() > 0
                    && absence.absenceMinutes() >= absence.shiftMinutes();
            AbsenceOutcome outcome = (coversWholeShift && outstanding == 0)
                    ? AbsenceOutcome.ND
                    : AbsenceOutcome.NO;

            verdicts.put(absence.absenceRecordId(),
                    new AbsenceVerdict(absence.absenceRecordId(), outcome, compensated));
        }

        // unmodifiableMap, not Map.copyOf: the latter returns an unordered map,
        // and the insertion order here is the chronological order the caller
        // reads the verdicts back in.
        return new Plan(List.copyOf(grants), Collections.unmodifiableMap(verdicts));
    }
}
