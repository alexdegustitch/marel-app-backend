package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.OperationDetailService;
import com.aleksandarparipovic.marel_app.operation.dto.OperationOutputPointDto;
import com.aleksandarparipovic.marel_app.operation.dto.OperationWorkLogRow;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operation page's read queries actually execute.
 *
 * <p>WHY THIS EXISTS. These are NATIVE queries with interface projections, and
 * that pair fails at RUNTIME, not at compile time: declaring `start_at` — a
 * `timestamptz` — as {@code OffsetDateTime} type-checks, builds, and then throws
 * {@code UnsupportedOperationException: Cannot project java.time.Instant to
 * java.time.OffsetDateTime} on the first real request. Nothing in the suite
 * executed them, so the screen was the test.
 *
 * <p>Executing the queries IS most of the assertion: a projection whose types or
 * column names drift from the SQL throws, and a schema change that renames a
 * column throws too.
 */
@Transactional
class OperationDetailReadsIT extends AbstractIntegrationTest {

    @Autowired private OperationDetailService operationDetailService;
    @Autowired private PayrollScenarioFixture fixture;

    @Test
    @DisplayName("recent work logs project the times, and the output buckets execute")
    void operationReadsExecute() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        // Today, so it lands inside the chart's own 30-day window.
        WorkShift shift = fixture.workShift(scenario.employee(), LocalDate.now(), 6, 480);
        Operation operation = fixture.operation(scenario.workCategory(), 40);

        fixture.workLog(shift, operation, scenario.workCategory(), 60, 120, 48);

        List<OperationWorkLogRow> logs = operationDetailService.getRecentWorkLogs(operation.getId(), 10);

        assertThat(logs).hasSize(1);
        OperationWorkLogRow row = logs.getFirst();
        assertThat(row.quantity()).isEqualTo(48);
        assertThat(row.workDate()).isEqualTo(shift.getWorkDate());
        // The span, not just the day: the point of the columns this test guards.
        assertThat(row.startAt().toInstant()).isEqualTo(shift.getStartAt().plusMinutes(60).toInstant());
        assertThat(row.endAt()).isAfter(row.startAt());
        assertThat(row.durationMin()).isEqualTo(120);
        // No production order on this log, so there is nothing for the row to open.
        assertThat(row.orderId()).isNull();
        assertThat(row.orderCode()).isNull();

        // Both granularities: one month is served by day, longer ranges by month.
        List<OperationOutputPointDto> daily = operationDetailService.getMonthlyOutput(operation.getId(), 1);
        List<OperationOutputPointDto> monthly = operationDetailService.getMonthlyOutput(operation.getId(), 6);

        assertThat(daily).hasSize(OperationDetailService.DAILY_WINDOW_DAYS);
        assertThat(daily).allSatisfy(point -> assertThat(point.period()).matches("\\d{4}-\\d{2}-\\d{2}"));
        assertThat(monthly).hasSize(6);
        assertThat(monthly).allSatisfy(point -> assertThat(point.period()).matches("\\d{4}-\\d{2}"));

        // The other two reads on the page run against the same operation.
        assertThat(operationDetailService.getProductionOrders(operation.getId())).isNotNull();
        assertThat(operationDetailService.getSampleOrders(operation.getId())).isNotNull();
        assertThat(operationDetailService.getArchiveBlockers(operation.getId())).isNotNull();
    }
}
