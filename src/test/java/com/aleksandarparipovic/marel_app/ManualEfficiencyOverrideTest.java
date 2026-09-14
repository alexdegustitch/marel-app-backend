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
 * An efficiency a supervisor typed over the measured one is the log's PAID rate,
 * used exactly as entered.
 *
 * <p>A unit test, because this is arithmetic at the one seam
 * ({@link WorkLogPerformanceCalculator#calculateApprovedPerformanceRate}) where
 * the paid rate is decided. From there it flows unchanged into the daily and
 * monthly reports, so the coefficient the user means — "novi učinak ÷ 100" — is
 * this value over 100.
 *
 * <p><b>The measured rate never changes.</b> Only the approved one does — the
 * override lives in its own column so the row keeps showing what was measured
 * ({@code performance_rate}) beside what was decided ({@code performance_rate_manual}).
 */
class ManualEfficiencyOverrideTest {

    private AppSettingService settings;
    private WorkLogPerformanceCalculator calculator;

    /** A ceiling low enough that any test asserting the manual value ignores it. */
    private static final BigDecimal LOW_CEILING = BigDecimal.valueOf(100);

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingService.class);
        when(settings.getMaxEfficiencyPercentAt(any())).thenReturn(LOW_CEILING);
        calculator = new WorkLogPerformanceCalculator(settings);
    }

    /**
     * A log measuring 70 % (28 against a norm of 40), optionally with a manual
     * efficiency typed over it.
     */
    private WorkLog log(String manualPercent) {
        Operation operation = new Operation();
        operation.setNormRequired(true);
        operation.setMinNorm(40);

        WorkLog log = new WorkLog();
        log.setOperation(operation);
        log.setHourlyOutput(new BigDecimal("28")); // 28/40 = 70 %
        log.setStartAt(OffsetDateTime.of(2026, 7, 6, 6, 0, 0, 0, ZoneOffset.UTC));
        if (manualPercent != null) {
            log.setPerformanceRateManual(new BigDecimal(manualPercent));
        }
        return log;
    }

    // ─── the rule ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a typed efficiency becomes the paid rate, exactly as entered")
    void typedEfficiencyIsThePaidRate() {
        // The program measured 70 %; the supervisor typed 80 %.
        assertThat(calculator.calculatePerformanceRate(log("80")))
                .isEqualByComparingTo("70");
        assertThat(calculator.calculateApprovedPerformanceRate(log("80"), false))
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("the manual value ignores the ceiling — 130 stays 130 under a 100 cap")
    void manualValueIgnoresTheCeiling() {
        assertThat(calculator.calculateApprovedPerformanceRate(log("130"), false))
                .isEqualByComparingTo("130");
    }

    @Test
    @DisplayName("the manual value overrides full-performance crediting (probation / šef sektora)")
    void manualValueOverridesFullPerformanceCredit() {
        // Credited full would be 100; the explicit decision of 80 wins.
        assertThat(calculator.calculateApprovedPerformanceRate(log("80"), true))
                .isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("zero is a valid manual efficiency")
    void zeroIsAllowed() {
        assertThat(calculator.calculateApprovedPerformanceRate(log("0"), false))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the measured rate is untouched by the override")
    void measuredRateIsNeverChanged() {
        assertThat(calculator.calculatePerformanceRate(log("80")))
                .isEqualByComparingTo("70");
    }

    // ─── nothing typed: existing behaviour is intact ─────────────────────────

    @Test
    @DisplayName("with no manual value the ceiling still applies as before")
    void noOverrideKeepsExistingBehaviour() {
        when(settings.getMaxEfficiencyPercentAt(any())).thenReturn(BigDecimal.valueOf(65));
        // Measured 70, capped to 65.
        assertThat(calculator.calculateApprovedPerformanceRate(log(null), false))
                .isEqualByComparingTo("65");
    }

    @Test
    @DisplayName("with no manual value, full-performance crediting still applies")
    void noOverrideStillCreditsFullPerformance() {
        when(settings.getMaxEfficiencyPercentAt(any())).thenReturn(BigDecimal.valueOf(1000));
        assertThat(calculator.calculateApprovedPerformanceRate(log(null), true))
                .isEqualByComparingTo("100");
    }
}
