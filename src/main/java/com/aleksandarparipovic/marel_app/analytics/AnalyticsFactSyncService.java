package com.aleksandarparipovic.marel_app.analytics;

import com.aleksandarparipovic.marel_app.production_order.ProductionOrder;
import com.aleksandarparipovic.marel_app.work_log.WorkLog;
import com.aleksandarparipovic.marel_app.work_log.WorkLogPerformanceCalculator;
import com.aleksandarparipovic.marel_app.work_shift.WorkShift;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

// Syncs work_log_facts (the analytics denormalized fact table) from the active WorkLogs of
// a shift. Called from DailyRecalcService.processJobWritePhase() with the same already-loaded
// `logs` list used throughout that method — no extra query needed here for the logs themselves.
// approved_performance_rate is computed via the shared WorkLogPerformanceCalculator (the same
// per-log formula the recalc engine uses), NOT read from WorkLog.approvedPerformanceRate, which
// is never populated by the recalc engine and is unreliable.
@Service
@RequiredArgsConstructor
public class AnalyticsFactSyncService {

    private static final String UPSERT_SQL = """
        INSERT INTO work_log_facts (
            work_log_id, work_shift_id, employee_id, operation_id, product_id,
            production_order_id, shift_type_id, work_date, month_start, shift_code,
            operation_start_time, product_name, operation_name, production_order_code,
            note, duration_min, quantity, scrap, approved_performance_rate, synced_at
        ) VALUES (
            :workLogId, :workShiftId, :employeeId, :operationId, :productId,
            :productionOrderId, :shiftTypeId, :workDate, :monthStart, :shiftCode,
            :operationStartTime, :productName, :operationName, :productionOrderCode,
            :note, :durationMin, :quantity, :scrap, :approvedPerformanceRate, now()
        )
        ON CONFLICT (work_log_id) DO UPDATE SET
            work_shift_id = EXCLUDED.work_shift_id,
            employee_id = EXCLUDED.employee_id,
            operation_id = EXCLUDED.operation_id,
            product_id = EXCLUDED.product_id,
            production_order_id = EXCLUDED.production_order_id,
            shift_type_id = EXCLUDED.shift_type_id,
            work_date = EXCLUDED.work_date,
            month_start = EXCLUDED.month_start,
            shift_code = EXCLUDED.shift_code,
            operation_start_time = EXCLUDED.operation_start_time,
            product_name = EXCLUDED.product_name,
            operation_name = EXCLUDED.operation_name,
            production_order_code = EXCLUDED.production_order_code,
            note = EXCLUDED.note,
            duration_min = EXCLUDED.duration_min,
            quantity = EXCLUDED.quantity,
            scrap = EXCLUDED.scrap,
            approved_performance_rate = EXCLUDED.approved_performance_rate,
            synced_at = now()
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkLogPerformanceCalculator performanceCalculator;

    @Transactional
    public void upsertFactsForShift(WorkShift workShift, List<WorkLog> logs) {
        List<WorkLog> active = logs.stream()
                .filter(l -> Boolean.TRUE.equals(l.getIsActive()) && l.getWorkCode() != null)
                .toList();

        if (active.isEmpty()) {
            jdbc.update(
                    "DELETE FROM work_log_facts WHERE work_shift_id = :workShiftId",
                    new MapSqlParameterSource("workShiftId", workShift.getId()));
            return;
        }

        List<Long> activeIds = active.stream().map(WorkLog::getId).toList();
        jdbc.update(
                "DELETE FROM work_log_facts WHERE work_shift_id = :workShiftId AND work_log_id NOT IN (:activeIds)",
                new MapSqlParameterSource()
                        .addValue("workShiftId", workShift.getId())
                        .addValue("activeIds", activeIds));

        SqlParameterSource[] batchParams = active.stream()
                .map(log -> toParams(workShift, log))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_SQL, batchParams);
    }

    private SqlParameterSource toParams(WorkShift workShift, WorkLog log) {
        BigDecimal approvedRate = performanceCalculator.calculateApprovedPerformanceRate(log);
        ProductionOrder order = log.getProductionOrder();

        return new MapSqlParameterSource()
                .addValue("workLogId", log.getId())
                .addValue("workShiftId", workShift.getId())
                .addValue("employeeId", workShift.getEmployee().getId())
                .addValue("operationId", log.getOperation().getId())
                .addValue("productId", log.getOperation().getProduct().getId())
                .addValue("productionOrderId", order != null ? order.getId() : null)
                .addValue("shiftTypeId", workShift.getShift().getId())
                .addValue("workDate", workShift.getWorkDate())
                .addValue("monthStart", workShift.getWorkDate().withDayOfMonth(1))
                .addValue("shiftCode", workShift.getShift().getShiftCode())
                .addValue("operationStartTime", log.getStartAt().toLocalTime())
                .addValue("productName", log.getOperation().getProduct().getProductName())
                .addValue("operationName", log.getOperation().getOpName())
                .addValue("productionOrderCode", order != null ? order.getCode() : null)
                .addValue("note", log.getNote())
                .addValue("durationMin", log.getDurationMin())
                .addValue("quantity", log.getQuantity() != null ? log.getQuantity() : 0)
                .addValue("scrap", log.getScrap() != null ? log.getScrap() : 0)
                .addValue("approvedPerformanceRate", approvedRate);
    }
}
