package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.app_settings.AppSettingService;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ceiling a month's efficiency is measured against.
 *
 * <p>{@code MonthlyRecalcService} used to set {@code approved_performance_rate} to
 * exactly {@code performance_rate}, so "approved" meant nothing at the monthly
 * level — while {@code DailySummaryService} had been capping the daily figure at
 * {@code max_efficiency_percent} all along. A month could show an approved
 * efficiency none of the days it is built from could.
 *
 * <p>The ceiling is resolved on the LAST day of the period. A month's efficiency
 * is the whole month's, so the limit in force when the month ended is the one it
 * was measured against — and reading it at {@code now()} would let a change made
 * in March quietly lift February's figure the next time February is recalculated.
 */
@Transactional
class MonthlyEfficiencyCeilingIT extends AbstractIntegrationTest {

    private static final String KEY = "max_efficiency_percent";

    @Autowired private AppSettingService appSettingService;
    @Autowired private PayrollScenarioFixture fixture;

    private static OffsetDateTime at(int year, int month, int day) {
        return OffsetDateTime.of(year, month, day, 0, 0, 0, 0, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("the ceiling is the one in force on the month's last day")
    void theCeilingIsTakenAtTheMonthsEnd() {
        // 120 % through February, raised to 150 % from 1 March.
        fixture.appSetting(KEY, new BigDecimal("120.00"), at(2020, 1, 1), at(2026, 2, 28));
        fixture.appSetting(KEY, new BigDecimal("150.00"), at(2026, 3, 1), null);

        assertThat(appSettingService.getMaxEfficiencyPercentOn(LocalDate.of(2026, 2, 28)))
                .isEqualByComparingTo("120.00");
        assertThat(appSettingService.getMaxEfficiencyPercentOn(LocalDate.of(2026, 3, 31)))
                .isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("raising the ceiling later does not lift an earlier month")
    void raisingItLaterDoesNotLiftAnEarlierMonth() {
        fixture.appSetting(KEY, new BigDecimal("120.00"), at(2020, 1, 1), at(2026, 2, 28));
        fixture.appSetting(KEY, new BigDecimal("150.00"), at(2026, 3, 1), null);

        // February recalculated today still measures against February's ceiling.
        // This is the whole reason the lookup takes a date instead of now().
        BigDecimal february = appSettingService.getMaxEfficiencyPercentOn(
                LocalDate.of(2026, 2, 1).withDayOfMonth(LocalDate.of(2026, 2, 1).lengthOfMonth()));

        assertThat(february).isEqualByComparingTo("120.00");
        assertThat(february).isNotEqualByComparingTo(
                appSettingService.getMaxEfficiencyPercentOn(LocalDate.of(2026, 3, 31)));
    }

    @Test
    @DisplayName("a month that ends the day a new ceiling starts still uses the old one")
    void theBoundaryBelongsToTheMonthThatEnds() {
        // valid_until is inclusive here: 28 February is still the old rate's day.
        fixture.appSetting(KEY, new BigDecimal("110.00"), at(2020, 1, 1), at(2026, 2, 28));
        fixture.appSetting(KEY, new BigDecimal("200.00"), at(2026, 3, 1), null);

        assertThat(appSettingService.getMaxEfficiencyPercentOn(LocalDate.of(2026, 2, 28)))
                .isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("with nothing configured the ceiling is 100 %")
    void theDefaultIsAHundred() {
        // Not zero. A missing setting must not cap every employee at no efficiency
        // at all — that would read as a payroll fault rather than a missing row.
        assertThat(appSettingService.getMaxEfficiencyPercentOn(LocalDate.of(2026, 9, 30)))
                .isEqualByComparingTo("100");
    }
}
