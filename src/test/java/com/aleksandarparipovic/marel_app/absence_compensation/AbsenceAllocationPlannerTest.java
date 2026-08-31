package com.aleksandarparipovic.marel_app.absence_compensation;

import com.aleksandarparipovic.marel_app.absence_record.AbsenceOutcome;
import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceAllocationPlanner.AbsenceInput;
import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceAllocationPlanner.BankEntry;
import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceAllocationPlanner.Grant;
import com.aleksandarparipovic.marel_app.absence_compensation.AbsenceAllocationPlanner.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allocation, checked against the cases the factory described.
 *
 * <p>Minutes throughout, and a regular shift is 480 of them.
 */
class AbsenceAllocationPlannerTest {

    private static final LocalDate AUG = LocalDate.of(2026, 8, 1);

    private static LocalDate aug(int day) {
        return AUG.withDayOfMonth(day);
    }

    private static BankEntry overtime(long id, int day, int minutes) {
        return new BankEntry(id, aug(day), minutes);
    }

    /** A partial absence: shorter than its shift, so it can never be a neradni dan. */
    private static AbsenceInput partial(long id, int day, int minutes) {
        return new AbsenceInput(id, aug(day), minutes, 480, true);
    }

    /** A full no-show: the absence covers the whole shift. */
    private static AbsenceInput fullDay(long id, int day, int shiftMinutes) {
        return new AbsenceInput(id, aug(day), shiftMinutes, shiftMinutes, true);
    }

    @Nested
    @DisplayName("which overtime pays")
    class WhichOvertimePays {

        @Test
        @DisplayName("three hours missed are paid oldest-overtime-first, and the plan names both days")
        void spendsTheBankOldestFirst() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(10L, 10, 60), overtime(12L, 12, 120)),
                    List.of(partial(1L, 21, 180)));

            assertThat(plan.grants()).containsExactly(
                    new Grant(1L, 10L, 60),
                    new Grant(1L, 12L, 120));
            assertThat(plan.verdicts().get(1L).compensatedMinutes()).isEqualTo(180);
        }

        @Test
        @DisplayName("a day's overtime is spent once, not once per absence")
        void neverSpendsTheSameMinuteTwice() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(10L, 10, 300)),
                    List.of(partial(1L, 11, 180), partial(2L, 12, 180)));

            assertThat(plan.grants()).containsExactly(
                    new Grant(1L, 10L, 180),
                    new Grant(2L, 10L, 120));
            assertThat(plan.verdicts().get(2L).compensatedMinutes()).isEqualTo(120);
        }

        @Test
        @DisplayName("with nothing in the bank nothing is granted and the absence stays NO")
        void anEmptyBankGrantsNothing() {
            Plan plan = AbsenceAllocationPlanner.plan(List.of(), List.of(fullDay(1L, 12, 480)));

            assertThat(plan.grants()).isEmpty();
            assertThat(plan.verdicts().get(1L).outcome()).isEqualTo(AbsenceOutcome.NO);
            assertThat(plan.verdicts().get(1L).compensatedMinutes()).isZero();
        }
    }

    @Nested
    @DisplayName("NO or ND")
    class NoOrNd {

        @Test
        @DisplayName("a full shift covered whole becomes a neradni dan")
        void aFullyCoveredFullDayIsNd() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(3L, 3, 480)),
                    List.of(fullDay(1L, 10, 480)));

            assertThat(plan.verdicts().get(1L).outcome()).isEqualTo(AbsenceOutcome.ND);
            assertThat(plan.verdicts().get(1L).compensatedMinutes()).isEqualTo(480);
        }

        @Test
        @DisplayName("a partial absence never becomes ND, however fully it is covered")
        void aPartialAbsenceIsNeverNd() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(3L, 3, 480)),
                    List.of(partial(1L, 10, 120)));

            assertThat(plan.verdicts().get(1L).outcome()).isEqualTo(AbsenceOutcome.NO);
            assertThat(plan.verdicts().get(1L).compensatedMinutes()).isEqualTo(120);
        }

        @Test
        @DisplayName("a ten-hour shift costs ten hours, not eight")
        void ndCostsTheShiftNotAFixedEight() {
            Plan short_ = AbsenceAllocationPlanner.plan(
                    List.of(overtime(3L, 3, 480)),
                    List.of(fullDay(1L, 10, 600)));
            assertThat(short_.verdicts().get(1L).outcome()).isEqualTo(AbsenceOutcome.NO);

            Plan enough = AbsenceAllocationPlanner.plan(
                    List.of(overtime(3L, 3, 600)),
                    List.of(fullDay(1L, 10, 600)));
            assertThat(enough.verdicts().get(1L).outcome()).isEqualTo(AbsenceOutcome.ND);
        }

        @Test
        @DisplayName("a full day the bank cannot cover whole is left alone entirely")
        void aFullDayIsAllOrNothing() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(3L, 3, 360)),
                    List.of(fullDay(1L, 10, 480)));

            assertThat(plan.grants()).isEmpty();
            assertThat(plan.verdicts().get(1L).outcome()).isEqualTo(AbsenceOutcome.NO);
            assertThat(plan.verdicts().get(1L).compensatedMinutes()).isZero();
        }

        @Test
        @DisplayName("and the hours it did not take are still there for a day that can use them")
        void andTheBankSurvivesForALaterDay() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(3L, 3, 360)),
                    List.of(fullDay(1L, 10, 480), fullDay(2L, 20, 360)));

            assertThat(plan.verdicts().get(1L).outcome()).isEqualTo(AbsenceOutcome.NO);
            assertThat(plan.verdicts().get(2L).outcome()).isEqualTo(AbsenceOutcome.ND);
            assertThat(plan.verdicts().get(2L).compensatedMinutes()).isEqualTo(360);
        }
    }

    @Nested
    @DisplayName("chronological order")
    class Chronological {

        /**
         * The factory's own example: two hours missed, then eight hours worked
         * over. The two hours are covered first, so six are left — and the next
         * full day off is NOT a neradni dan, because six will not buy eight.
         */
        @Test
        @DisplayName("an earlier partial absence spends the bank before a later full day sees it")
        void anEarlierAbsenceSpendsFirst() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(10L, 10, 480)),
                    List.of(partial(1L, 5, 120), fullDay(2L, 20, 480)));

            assertThat(plan.verdicts().get(1L).compensatedMinutes()).isEqualTo(120);
            assertThat(plan.verdicts().get(2L).outcome()).isEqualTo(AbsenceOutcome.NO);
            // Nothing at all, not the 360 that were left: a full day is all or nothing.
            assertThat(plan.verdicts().get(2L).compensatedMinutes()).isZero();
        }

        /**
         * The supervisor adds two hours to an earlier shift. Nothing about the
         * absences changed, but the bank now covers the whole day, and the day
         * that stayed NO becomes ND on the next pass.
         */
        @Test
        @DisplayName("overtime added later turns a refused day into a neradni dan")
        void aBiggerBankBuysTheDayAfterAll() {
            List<AbsenceInput> sameAbsences = List.of(partial(1L, 5, 120), fullDay(2L, 20, 480));

            Plan before = AbsenceAllocationPlanner.plan(List.of(overtime(10L, 10, 480)), sameAbsences);
            assertThat(before.verdicts().get(2L).outcome()).isEqualTo(AbsenceOutcome.NO);

            Plan after = AbsenceAllocationPlanner.plan(List.of(overtime(10L, 10, 600)), sameAbsences);
            assertThat(after.verdicts().get(2L).outcome()).isEqualTo(AbsenceOutcome.ND);
            assertThat(after.verdicts().get(2L).compensatedMinutes()).isEqualTo(480);
        }

        @Test
        @DisplayName("without that earlier absence the same bank does buy the day")
        void andWithoutItTheDayIsBought() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(10L, 10, 480)),
                    List.of(fullDay(2L, 20, 480)));

            assertThat(plan.verdicts().get(2L).outcome()).isEqualTo(AbsenceOutcome.ND);
        }

        @Test
        @DisplayName("verdicts come back in the order they were given")
        void verdictsKeepTheirOrder() {
            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(10L, 10, 480)),
                    List.of(partial(7L, 5, 60), partial(3L, 6, 60), partial(9L, 7, 60)));

            assertThat(plan.verdicts().keySet()).containsExactly(7L, 3L, 9L);
        }
    }

    @Nested
    @DisplayName("absences that take no part")
    class NotCompensable {

        @Test
        @DisplayName("a paid absence gets no outcome and leaves the bank alone")
        void aPaidAbsenceIsUntouched() {
            AbsenceInput paidLeave = new AbsenceInput(1L, aug(5), 480, 480, false);

            Plan plan = AbsenceAllocationPlanner.plan(
                    List.of(overtime(10L, 10, 480)),
                    List.of(paidLeave, fullDay(2L, 20, 480)));

            assertThat(plan.verdicts().get(1L).outcome()).isNull();
            assertThat(plan.verdicts().get(1L).compensatedMinutes()).isZero();
            // The bank was never touched by it, so the later day is still bought.
            assertThat(plan.verdicts().get(2L).outcome()).isEqualTo(AbsenceOutcome.ND);
        }
    }

    @Test
    @DisplayName("the same inputs give the same plan twice — an unchanged month must not rewrite itself")
    void isDeterministic() {
        List<BankEntry> bank = List.of(overtime(10L, 10, 200), overtime(12L, 12, 200));
        List<AbsenceInput> absences = List.of(partial(1L, 11, 150), fullDay(2L, 20, 480));

        assertThat(AbsenceAllocationPlanner.plan(bank, absences))
                .isEqualTo(AbsenceAllocationPlanner.plan(bank, absences));
    }
}
