package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.daily_report.DailyReportService;
import com.aleksandarparipovic.marel_app.daily_report.dto.DailyReportCreateRequest;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day-skeleton POST is find-or-create.
 *
 * <p>WHY THIS EXISTS. Creating a shift fires two writers at the same day: the
 * frontend follows its create with POST /daily-reports (the skeleton), and a
 * recalc enqueued at creation (a shift created WITH its pauza does this
 * immediately) can have the worker build the real report first. The skeleton
 * insert then died on uq_daily, the whole creation read as failed, and the
 * modal stayed open over a shift that in fact existed.
 */
@Transactional
class DailyReportSkeletonIdempotencyIT extends AbstractIntegrationTest {

    @Autowired private DailyReportService dailyReportService;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("a second skeleton for the same shift returns the existing row instead of dying on uq_daily")
    void secondSkeletonReturnsTheExistingRow() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        var shift = fixture.workShift(scenario.employee(), LocalDate.of(2026, 9, 16), 6, 480);

        DailyReportCreateRequest request = new DailyReportCreateRequest();
        request.setEmployeeId(scenario.employee().getId());
        request.setWorkShiftId(shift.getId());
        request.setWorkDate(shift.getWorkDate().toString());

        Long first = dailyReportService.create(request).id();
        Long second = dailyReportService.create(request).id();

        assertThat(second).isEqualTo(first);
        assertThat(dailyReportService.findCreateResponseByWorkShiftId(shift.getId()))
                .hasValueSatisfying(response -> assertThat(response.id()).isEqualTo(first));
    }
}
