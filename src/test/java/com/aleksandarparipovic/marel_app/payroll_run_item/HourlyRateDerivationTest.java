package com.aleksandarparipovic.marel_app.payroll_run_item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the month is calculated at, given the three things it is derived from.
 *
 * <p>The rate used to be a single stored number that a person either typed or
 * did not. A performance mark makes that one field too few: the typed value and
 * the value in force stop being the same thing, and if they share a column then
 * applying a mark overwrites what it was applied TO. Two consequences follow,
 * and both are tested here — a mark cannot be applied twice and compound, and
 * taking it back is exact rather than a division that loses cents.
 */
class HourlyRateDerivationTest {

    private static PayrollRunItem item(BigDecimal system, BigDecimal manual,
                                       BigDecimal mark, boolean applied) {
        PayrollRunItem item = new PayrollRunItem();
        item.setHourlyRateSystem(system);
        item.setHourlyRateManual(manual);
        item.setPerformanceMark(mark);
        item.setPerformanceMarkApplied(applied);
        return item;
    }

    private static BigDecimal rsd(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("the base")
    class Base {

        @Test
        @DisplayName("is the system rate when nobody typed one")
        void systemWhenNothingTyped() {
            assertThat(item(rsd("500.00"), null, null, false).baseHourlyRate())
                    .isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("is the typed rate when somebody did")
        void typedWins() {
            assertThat(item(rsd("500.00"), rsd("620.00"), null, false).baseHourlyRate())
                    .isEqualByComparingTo("620.00");
        }

        @Test
        @DisplayName("is zero when neither exists, rather than null")
        void zeroWhenNeither() {
            // 923 of 949 live items calculate at rate 0 and must keep doing so;
            // a null here would reach the multiplication below.
            assertThat(item(null, null, null, false).baseHourlyRate())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("is a typed ZERO, not the system rate — zero is a decision somebody made")
        void typedZeroIsNotAbsence() {
            assertThat(item(rsd("500.00"), rsd("0.00"), null, false).baseHourlyRate())
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("the rate in force")
    class InForce {

        @Test
        @DisplayName("is the base while the mark is only GIVEN, not applied")
        void markAloneChangesNothing() {
            // The whole reason a supervisor may give a mark: it moves no money.
            assertThat(item(rsd("500.00"), null, rsd("1.10"), false).effectiveHourlyRate())
                    .isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("is the base times the mark once it is applied")
        void appliedMarkMultiplies() {
            assertThat(item(rsd("500.00"), null, rsd("1.10"), true).effectiveHourlyRate())
                    .isEqualByComparingTo("550.00");
        }

        @Test
        @DisplayName("multiplies the TYPED rate when there is one, not the system's")
        void appliedMarkMultipliesTheTypedRate() {
            assertThat(item(rsd("500.00"), rsd("620.00"), rsd("0.90"), true).effectiveHourlyRate())
                    .isEqualByComparingTo("558.00");
        }

        @Test
        @DisplayName("follows the system rate when THAT moves under an applied mark")
        void systemRateMovesUnderAnAppliedMark() {
            /*
             * The reason the base is read fresh instead of snapshotted before the
             * mark was applied. A snapshot would leave this item at 550.00 — the
             * old rate times the mark — and quietly stop tracking the raise.
             */
            assertThat(item(rsd("600.00"), null, rsd("1.10"), true).effectiveHourlyRate())
                    .isEqualByComparingTo("660.00");
        }

        @Test
        @DisplayName("cannot compound: deriving twice from the same inputs gives the same rate")
        void applyingIsIdempotent() {
            PayrollRunItem it = item(rsd("500.00"), null, rsd("1.10"), true);

            BigDecimal once = it.effectiveHourlyRate();
            BigDecimal twice = it.effectiveHourlyRate();

            // The old shape — multiply hourly_rate in place — made a second apply
            // produce 605.00. Nothing here reads the previous result.
            assertThat(once).isEqualByComparingTo("550.00");
            assertThat(twice).isEqualByComparingTo(once);
        }

        @Test
        @DisplayName("returns EXACTLY to the base when the mark is taken out of force")
        void revertIsExact() {
            /*
             * 33.33 * 1.15 rounds to 38.33; dividing that back by 1.15 gives
             * 33.3304…, which rounds to 33.33 here but does not in general. The
             * base was never overwritten, so reverting reads it rather than
             * reconstructing it.
             */
            PayrollRunItem applied = item(rsd("500.00"), rsd("33.33"), rsd("1.15"), true);
            assertThat(applied.effectiveHourlyRate()).isEqualByComparingTo("38.33");

            PayrollRunItem reverted = item(rsd("500.00"), rsd("33.33"), rsd("1.15"), false);
            assertThat(reverted.effectiveHourlyRate()).isEqualByComparingTo("33.33");
        }

        @Test
        @DisplayName("a mark of 1 changes nothing, and a mark of 0 pays nothing by the hour")
        void boundsOfTheRange() {
            assertThat(item(rsd("500.00"), null, rsd("1.00"), true).effectiveHourlyRate())
                    .isEqualByComparingTo("500.00");
            assertThat(item(rsd("500.00"), null, rsd("0.00"), true).effectiveHourlyRate())
                    .isEqualByComparingTo("0.00");
            assertThat(item(rsd("500.00"), null, rsd("2.00"), true).effectiveHourlyRate())
                    .isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("an applied flag with no mark behind it leaves the base alone")
        void appliedWithoutAMarkIsHarmless() {
            // The schema forbids this pairing; the derivation still refuses to
            // multiply by nothing rather than throwing on a row somebody wrote by
            // hand before the constraint existed.
            assertThat(item(rsd("500.00"), null, null, true).effectiveHourlyRate())
                    .isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("rounds to two decimals, the scale every money column on this table uses")
        void roundsToCents() {
            // 454.55 * 1.1 = 500.005 → 500.01 half-up.
            assertThat(item(rsd("454.55"), null, rsd("1.10"), true).effectiveHourlyRate())
                    .isEqualByComparingTo("500.01");
            assertThat(item(rsd("454.55"), null, rsd("1.10"), true).effectiveHourlyRate().scale())
                    .isEqualTo(2);
        }
    }
}
