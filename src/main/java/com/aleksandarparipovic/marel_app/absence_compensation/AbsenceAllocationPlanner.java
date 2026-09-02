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
 *   <li><b>Requested days first.</b> A person who marks a day AS a neradni dan
 *       is making a choice the chronological rule cannot express — they know
 *       which day should be bought back. Those are covered before anything
 *       else, in date order among themselves.</li>
 *   <li><b>Then chronological.</b> Whatever is left is spent in the order the
 *       absences happened. An absence early in the month spends the bank before
 *       a later one sees it, even when the later one would have made better use
 *       of it.</li>
 *   <li><b>Oldest overtime first.</b> Within one absence, the bank is spent
 *       FIFO, so the record reads "two hours from the 12th, one from the 10th"
 *       rather than naming whichever day happened to be loaded first.</li>
 *   <li><b>ND needs the WHOLE shift.</b> An absence becomes a neradni dan only
 *       when it covers its entire shift AND the bank could pay for all of it.</li>
 *   <li><b>A DECLARED neradni dan is not bought at all.</b> A day entered AS a
 *       neradni dan while the bank could not have covered it carries the ND
 *       category rather than NO, and arrives here as non-compensable. It is a
 *       day nobody was expected in, not an absence to be made up: it stays ND,
 *       spends nothing, and leaves the bank for the days that do need it. See
 *       {@code ShiftAbsenceSync}, which decides which of the two a person's ND
 *       entry is — once, when it is entered.</li>
 *   <li><b>Nothing is held back for a better day.</b> Every absence takes what
 *       the bank has when its turn comes, whether or not that buys anything.
 *       Six hours against an eight-hour no-show buys no ND and still spends the
 *       six — the order absences happened decides, not what the hours could
 *       have bought later.</li>
 *   <li><b>One month, no carry-over.</b> The caller passes one month's overtime
 *       and one month's absences; nothing here reaches past either end.</li>
 * </ol>
 *
 * <p>A partly covered absence keeps {@link AbsenceOutcome#NO} and still spoils
 * that week's weekend bonus. Compensation never makes absent time PAID — it buys
 * the day's standing, not its wage.
 *
 * <p>Because the whole month is planned again from scratch every time, a day
 * that fell short today is bought the moment the bank grows: a supervisor who
 * adds two hours to an earlier shift turns a six-hour bank into eight, and the
 * no-show that stayed NO becomes ND without anybody revisiting it.
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
     * @param requestedNd     the day was entered AS a neradni dan; covered before
     *                        anything the allocation decides for itself
     */
    public record AbsenceInput(Long absenceRecordId,
                               LocalDate workDate,
                               int absenceMinutes,
                               int shiftMinutes,
                               boolean compensable,
                               boolean requestedNd) {

        /** Everything the allocation decides on its own. */
        public AbsenceInput(Long absenceRecordId, LocalDate workDate,
                            int absenceMinutes, int shiftMinutes, boolean compensable) {
            this(absenceRecordId, workDate, absenceMinutes, shiftMinutes, compensable, false);
        }
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

        /*
         * REQUESTED DAYS ARE SERVED FIRST, and the rest keeps its order.
         *
         * Both passes walk the caller's list, so the chronological order the
         * caller established survives inside each. Only the precedence between
         * the two is decided here.
         */
        List<AbsenceInput> ordered = new ArrayList<>(absences.size());
        absences.stream().filter(AbsenceInput::requestedNd).forEach(ordered::add);
        absences.stream().filter(a -> !a.requestedNd()).forEach(ordered::add);

        for (AbsenceInput absence : ordered) {
            if (!absence.compensable()) {
                /*
                 * TWO KINDS OF ABSENCE REACH HERE, and they part on one flag.
                 *
                 * A DECLARED neradni dan — requested, and carrying the ND
                 * category itself — is a day that was never a working day. There
                 * is nothing for the bank to buy: it keeps ND whatever the bank
                 * holds, spends none of it, and its minutes stay on the day under
                 * ND rather than under NO. That is the point of declaring one,
                 * and it is why the bank is left for the days that do need it.
                 *
                 * A PAID absence — GO, PLO, SO — gets no outcome at all rather
                 * than NO, so that "NO" keeps meaning the one thing it means:
                 * unpaid, uncovered, and spoiling the weekend bonus.
                 */
                AbsenceOutcome outcome = absence.requestedNd() ? AbsenceOutcome.ND : null;
                verdicts.put(absence.absenceRecordId(),
                        new AbsenceVerdict(absence.absenceRecordId(), outcome, 0));
                continue;
            }

            // Covering the whole shift is what makes a day a NON-working day. A
            // shift of ten hours therefore costs ten, not eight: the day is only
            // one the employee was never expected to work if none of it is left
            // standing as absence.
            boolean coversWholeShift = absence.shiftMinutes() > 0
                    && absence.absenceMinutes() >= absence.shiftMinutes();

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

            AbsenceOutcome outcome = (coversWholeShift && outstanding == 0)
                    ? AbsenceOutcome.ND
                    : AbsenceOutcome.NO;

            verdicts.put(absence.absenceRecordId(),
                    new AbsenceVerdict(absence.absenceRecordId(), outcome, compensated));
        }

        // unmodifiableMap, not Map.copyOf: the latter returns an unordered map,
        // and the insertion order here is the chronological order the caller
        // reads the verdicts back in.
        // Back into the order the caller gave, so a plan reads down the month
        // rather than down the requests.
        Map<Long, AbsenceVerdict> inCallerOrder = new LinkedHashMap<>();
        for (AbsenceInput absence : absences) {
            AbsenceVerdict verdict = verdicts.get(absence.absenceRecordId());
            if (verdict != null) {
                inCallerOrder.put(absence.absenceRecordId(), verdict);
            }
        }

        return new Plan(List.copyOf(grants), Collections.unmodifiableMap(inCallerOrder));
    }

}
