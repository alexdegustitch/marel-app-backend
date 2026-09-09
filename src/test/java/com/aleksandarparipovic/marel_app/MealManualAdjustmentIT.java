package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.daily_report.DailyReport;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportRepository;
import com.aleksandarparipovic.marel_app.daily_report.DailyReportService;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportDto;
import com.aleksandarparipovic.marel_app.daily_report.dto.MealAdjustmentRequest;
import com.aleksandarparipovic.marel_app.employee.Employee;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A meal somebody adds or takes back by hand (V38).
 *
 * <p>The hand correction is a DELTA beside the computed {@code meals_count},
 * never an override of it: the recalculation stays the owner of the computed
 * figure, the correction survives every rebuild, and the karton can always
 * show both. The effective count — what the month pays — is
 * {@code max(0, computed + delta)} and is derived on read.
 */
@Transactional
class MealManualAdjustmentIT extends AbstractIntegrationTest {

    @Autowired private DailyReportService dailyReportService;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private EntityManager em;

    private static final LocalDate DAY = LocalDate.of(2026, 7, 6);

    private DailyReport aReportWithOneComputedMeal() {
        Employee employee = fixture.scenario().build().employee();
        // 480 worked minutes → the fixture writes meals_count = 1.
        return fixture.dailyReport(employee, DAY, 480, 480);
    }

    @Test
    @DisplayName("a correction is stored beside the computed figure, not instead of it")
    void aCorrectionIsStoredBesideTheComputedFigure() {
        DailyReport report = aReportWithOneComputedMeal();

        DailyReportDto dto = dailyReportService.adjustMeals(
                report.getWorkShift().getId(), new MealAdjustmentRequest(1, "radio na dve mašine"));

        assertThat(dto.getMealsCount()).isEqualTo(1);
        assertThat(dto.getMealsManualDelta()).isEqualTo(1);
        assertThat(dto.getEffectiveMealsCount()).isEqualTo(2);
        assertThat(dto.getMealsManualNote()).isEqualTo("radio na dve mašine");

        DailyReport reloaded = dailyReportRepository.findById(report.getId()).orElseThrow();
        assertThat(reloaded.getMealsCount()).isEqualTo(1);
        assertThat(reloaded.getMealsManualDelta()).isEqualTo(1);
        assertThat(reloaded.getMealsManualAt()).isNotNull();
    }

    @Test
    @DisplayName("taking one back works down to zero, never below it")
    void takingBackStopsAtZero() {
        DailyReport report = aReportWithOneComputedMeal();
        Long shiftId = report.getWorkShift().getId();

        DailyReportDto dto = dailyReportService.adjustMeals(shiftId, new MealAdjustmentRequest(-1, null));
        assertThat(dto.getEffectiveMealsCount()).isEqualTo(0);

        // Computed 1, correction −2 would owe the employee a meal. Refused with
        // the figures in the sentence, not clamped silently.
        assertThatThrownBy(() ->
                dailyReportService.adjustMeals(shiftId, new MealAdjustmentRequest(-2, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ispod nule");
    }

    @Test
    @DisplayName("a delta of zero clears the correction, authorship included")
    void zeroClearsTheCorrection() {
        DailyReport report = aReportWithOneComputedMeal();
        Long shiftId = report.getWorkShift().getId();

        dailyReportService.adjustMeals(shiftId, new MealAdjustmentRequest(1, "greškom"));
        DailyReportDto cleared = dailyReportService.adjustMeals(shiftId, new MealAdjustmentRequest(0, null));

        assertThat(cleared.getMealsManualDelta()).isEqualTo(0);
        assertThat(cleared.getMealsManualNote()).isNull();
        assertThat(cleared.getEffectiveMealsCount()).isEqualTo(1);

        DailyReport reloaded = dailyReportRepository.findById(report.getId()).orElseThrow();
        assertThat(reloaded.getMealsManualAt()).isNull();
        assertThat(reloaded.getMealsManualBy()).isNull();
    }

    @Test
    @DisplayName("the month is requeued, because meal_allowance_num is a sum over effective counts")
    void theMonthIsRequeued() {
        DailyReport report = aReportWithOneComputedMeal();

        dailyReportService.adjustMeals(report.getWorkShift().getId(), new MealAdjustmentRequest(1, null));

        Number queued = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM monthly_report_recalc_queue "
                                + "WHERE employee_id = ?1 AND report_year = ?2 AND report_month = ?3 "
                                + "AND reason = 'MEAL_MANUAL_ADJUSTMENT'")
                .setParameter(1, report.getEmployee().getId())
                .setParameter(2, DAY.getYear())
                .setParameter(3, DAY.getMonthValue())
                .getSingleResult();
        assertThat(queued.longValue()).isEqualTo(1L);
    }
}
