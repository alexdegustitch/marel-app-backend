package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsOptionDto;
import com.aleksandarparipovic.marel_app.analytics.dto.OperationEmployeeDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductOperationSummaryDto;
import com.aleksandarparipovic.marel_app.analytics.repository.AnalyticsQueryRepository;
import com.aleksandarparipovic.marel_app.operation.Operation;
import com.aleksandarparipovic.marel_app.operation.repository.OperationRepository;
import com.aleksandarparipovic.marel_app.support.AbstractIntegrationTest;
import com.aleksandarparipovic.marel_app.support.PayrollScenarioFixture;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics labels come from the operation and product, not from the copy stored
 * on the fact row.
 *
 * <p>WHY THIS EXISTS. `work_log_facts` denormalizes `operation_name` and
 * `product_name` at sync time. Grouping by those copies meant that renaming an
 * operation — which the operation page now offers — split ONE operation's totals
 * into two report rows, one per spelling, and listed it twice in the filter. The
 * numbers were wrong, not just the labels.
 *
 * <p>The fixture writes fact rows directly: the point is what the QUERIES do with
 * a fact row whose stored name has gone stale, and going through the recalc
 * worker would refresh that name and hide the very case under test.
 */
@Transactional
class AnalyticsLabelsFollowRenamesIT extends AbstractIntegrationTest {

    @Autowired private AnalyticsQueryRepository analyticsQueryRepository;
    @Autowired private OperationRepository operationRepository;
    @Autowired private PayrollScenarioFixture fixture;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("a renamed operation stays one row, under its current name")
    void renamedOperationIsNotSplit() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        WorkShift shift = fixture.workShift(scenario.employee(), LocalDate.of(2026, 5, 4), 6, 480);
        Operation operation = fixture.operation(scenario.workCategory(), 40);
        operationRepository.saveAndFlush(operation);

        // Two fact rows for the SAME operation, carrying the name as it stood when
        // each was synced — exactly what a rename between two recalcs leaves behind.
        insertFact(shift, operation, fixture.workLog(shift, operation, scenario.workCategory(), 0, 60, 100),
                "Kantovanje", 100, 60);
        insertFact(shift, operation, fixture.workLog(shift, operation, scenario.workCategory(), 120, 30, 50),
                "Kantovanje ABS", 50, 30);

        operation.setOpName("Kantovanje ABS 2mm");
        operationRepository.saveAndFlush(operation);

        AnalyticsFilterRequest filter = new AnalyticsFilterRequest();
        long operationId = operation.getId();

        List<ProductOperationSummaryDto> productOperation =
                analyticsQueryRepository.findProductOperationSummary(filter).stream()
                        .filter(row -> operationId == row.getOperationId())
                        .toList();

        assertThat(productOperation).hasSize(1);
        assertThat(productOperation.getFirst().getOperationName()).isEqualTo("Kantovanje ABS 2mm");
        // The whole point: both rows counted, under one operation.
        assertThat(productOperation.getFirst().getSumQuantity()).isEqualTo(150);

        // The same must hold of the operation report, which now groups by (operation, worker)
        // — one worker here, so still one row for the operation.
        AnalyticsFilterRequest operationFilter = new AnalyticsFilterRequest();
        operationFilter.setGroupByOperation(true);
        operationFilter.setSize(200);
        List<OperationEmployeeDto> operationEfficiency =
                analyticsQueryRepository.findOperationEfficiencyPage(operationFilter).content().stream()
                        .filter(row -> operationId == row.getOperationId())
                        .toList();

        assertThat(operationEfficiency).hasSize(1);
        assertThat(operationEfficiency.getFirst().getSumQuantity()).isEqualTo(150);
        // Under the CURRENT name, not the one copied onto the facts when they were synced.
        assertThat(operationEfficiency.getFirst().getOperationName()).isEqualTo("Kantovanje ABS 2mm");

        // The filter list offers the operation once, under the current name.
        List<AnalyticsOptionDto> options = analyticsQueryRepository.findDistinctOperations(null, 500).stream()
                .filter(option -> operationId == option.id())
                .toList();

        assertThat(options).hasSize(1);
        assertThat(options.getFirst().label()).isEqualTo("Kantovanje ABS 2mm");
    }

    @Test
    @DisplayName("the operation filter searches on the server and honours its limit")
    void operationOptionsAreSearchable() {
        PayrollScenarioFixture.Scenario scenario = fixture.scenario().build();
        WorkShift shift = fixture.workShift(scenario.employee(), LocalDate.of(2026, 5, 5), 6, 480);

        String marker = "IT" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            Operation operation = fixture.operation(scenario.workCategory(), 40);
            operation.setOpName(marker + "-Brusenje-" + i);
            operationRepository.saveAndFlush(operation);
            insertFact(shift, operation,
                    fixture.workLog(shift, operation, scenario.workCategory(), i * 30, 20, 10), marker, 10, 20);
        }

        Operation other = fixture.operation(scenario.workCategory(), 40);
        other.setOpName(marker + "-Lakiranje");
        operationRepository.saveAndFlush(other);
        insertFact(shift, other,
                fixture.workLog(shift, other, scenario.workCategory(), 200, 20, 10), marker, 10, 20);

        assertThat(analyticsQueryRepository.findDistinctOperations(marker, 200)).hasSize(4);
        // Case-insensitive, matches anywhere in the name.
        assertThat(analyticsQueryRepository.findDistinctOperations("brusenje", 200)).hasSize(3);
        assertThat(analyticsQueryRepository.findDistinctOperations(marker, 2)).hasSize(2);
    }

    /**
     * One fact row, written straight to SQL with the operation name as it stood
     * at sync time. Going through the recalc worker would write the CURRENT name
     * and hide the very case under test.
     */
    private void insertFact(WorkShift shift, Operation operation, WorkLog workLog,
                            String storedOperationName, int quantity, int durationMin) {
        jdbc.update("""
                INSERT INTO work_log_facts (
                    work_log_id, work_shift_id, employee_id, operation_id, product_id,
                    shift_type_id, work_date, month_start, shift_code, operation_start_time,
                    product_name, operation_name, duration_min, quantity, scrap)
                SELECT ?, ws.id, ws.employee_id, ?, ?, ws.shift_id, ws.work_date,
                       date_trunc('month', ws.work_date)::date, 'IT', '06:00',
                       'stari naziv proizvoda', ?, ?, ?, 0
                FROM work_shifts ws WHERE ws.id = ?
                """,
                workLog.getId(), operation.getId(), operation.getProduct().getId(),
                storedOperationName, durationMin, quantity, shift.getId());
    }
}
