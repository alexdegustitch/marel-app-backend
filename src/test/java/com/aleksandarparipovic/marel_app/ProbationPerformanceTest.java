package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.WorkLogPerformanceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Work done on probation is credited at 100 %.
 *
 * <p>A unit test, because this is arithmetic: no database is involved, so it runs
 * in the fast loop. Who is on probation is {@code ProbationPolicy}'s question and
 * is covered by {@code ProbationWeekendBonusIT}.
 *
 * <p><b>The measured rate never changes.</b> Only the APPROVED one does — the two
 * columns exist precisely so a payslip can show what somebody actually produced
 * beside what they were paid for.
 */
class ProbationPerformanceTest {

    private AppSettingService settings;
    private WorkLogPerformanceCalculator calculator;

    /** No ceiling in the way, so probation is the only thing moving the number. */
    private static final BigDecimal NO_CEILING = BigDecimal.valueOf(1000);

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingService.class);
        when(settings.getMaxEfficiencyPercentAt(any())).thenReturn(NO_CEILING);
        calculator = new WorkLogPerformanceCalculator(settings);
    }

    /** @param hourlyOutput what they produced per hour, against a norm of 40. */
    private WorkLog log(String hourlyOutput) {
        Operation operation = new Operation();
        operation.setNormRequired(true);
        operation.setMinNorm(40);

        WorkLog log = new WorkLog();
        log.setOperation(operation);
        log.setHourlyOutput(new BigDecimal(hourlyOutput));
        log.setStartAt(OffsetDateTime.of(2026, 7, 6, 6, 0, 0, 0, ZoneOffset.UTC));
        return log;
    }

    // ─── the rule ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("under the norm on probation is credited at 100 %")
    void underTheNormIsLiftedToOneHundred() {
        // 35 against a norm of 40 measures 87.5 %.
        assertThat(calculator.calculatePerformanceRate(log("35")))
                .isEqualByComparingTo("87.5");
        assertThat(calculator.calculateApprovedPerformanceRate(log("35"), true))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("over the norm on probation is also credited at 100 %")
    void overTheNormIsBroughtDownToOneHundred() {
        // 50 against 40 measures 125 %. This is why probation cannot be expressed
        // as another ceiling: it moves the figure in BOTH directions.
        assertThat(calculator.calculatePerformanceRate(log("50")))
                .isEqualByComparingTo("125");
        assertThat(calculator.calculateApprovedPerformanceRate(log("50"), true))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("the measured rate is untouched — the payslip still shows what really happened")
    void measuredRateIsNeverChanged() {
        // calculatePerformanceRate takes no probation argument at all, which is the
        // structural guarantee: nothing can dress the real figure up as 100 %.
        assertThat(calculator.calculatePerformanceRate(log("35")))
                .isEqualByComparingTo("87.5");
        assertThat(calculator.calculatePerformanceRate(log("50")))
                .isEqualByComparingTo("125");
    }

    @Test
    @DisplayName("off probation, nothing changes")
    void offProbationIsUnchanged() {
        assertThat(calculator.calculateApprovedPerformanceRate(log("35"), false))
                .isEqualByComparingTo("87.5");
        assertThat(calculator.calculateApprovedPerformanceRate(log("50"), false))
                .isEqualByComparingTo("125");
    }

    // ─── the ceiling still wins when it is lower ────────────────────────────

    @Test
    @DisplayName("a ceiling below 100 beats probation")
    void ceilingBelowOneHundredWins() {
        // The owner's rule. Probation substitutes 100 for what was MEASURED; it
        // does not lift the limit on what may be paid.
        when(settings.getMaxEfficiencyPercentAt(any())).thenReturn(BigDecimal.valueOf(90));

        assertThat(calculator.calculateApprovedPerformanceRate(log("35"), true))
                .isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("a ceiling above 100 does not lift probation above 100")
    void ceilingAboveOneHundredDoesNotLift() {
        when(settings.getMaxEfficiencyPercentAt(any())).thenReturn(BigDecimal.valueOf(120));

        // Measured 125 would be capped to 120 normally; on probation it is 100.
        assertThat(calculator.calculateApprovedPerformanceRate(log("50"), false))
                .isEqualByComparingTo("120");
        assertThat(calculator.calculateApprovedPerformanceRate(log("50"), true))
                .isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("the ceiling is unchanged for everybody not on probation")
    void ordinaryCeilingBehaviourIsIntact() {
        when(settings.getMaxEfficiencyPercentAt(any())).thenReturn(BigDecimal.valueOf(120));

        assertThat(calculator.calculateApprovedPerformanceRate(log("40"), false))
                .isEqualByComparingTo("100");
        assertThat(calculator.calculateApprovedPerformanceRate(log("35"), false))
                .isEqualByComparingTo("87.5");
    }

    // ─── an operation with no norm ──────────────────────────────────────────

    @Test
    @DisplayName("an operation with no norm is 100 % either way")
    void noNormIsOneHundredRegardless() {
        Operation operation = new Operation();
        operation.setNormRequired(false);
        WorkLog log = new WorkLog();
        log.setOperation(operation);
        log.setStartAt(OffsetDateTime.of(2026, 7, 6, 6, 0, 0, 0, ZoneOffset.UTC));

        assertThat(calculator.calculateApprovedPerformanceRate(log, false))
                .isEqualByComparingTo("100");
        assertThat(calculator.calculateApprovedPerformanceRate(log, true))
                .isEqualByComparingTo("100");
    }
}
