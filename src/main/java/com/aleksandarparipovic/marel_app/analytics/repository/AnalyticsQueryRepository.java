package com.aleksandarparipovic.marel_app.analytics.repository;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsOptionDto;
import com.aleksandarparipovic.marel_app.analytics.dto.EmployeeEfficiencyDto;
import com.aleksandarparipovic.marel_app.analytics.dto.OperationEfficiencyDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductDateOperationEmployeeDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductOperationSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

// Hand-built native SQL query layer for the 5 analytics report pages, all reading from the
// denormalized work_log_facts table (see AnalyticsFactSyncService). NamedParameterJdbcTemplate
// is used instead of Spring Data @Query nativeQuery because each page has 9-12 independent
// optional filters simultaneously — a static "(:x IS NULL OR ...)" query string at that count
// becomes unreadable, whereas appendCommonFilters below appends a WHERE fragment only when a
// filter is actually populated (easier for the planner, easier to read).
@Repository
@RequiredArgsConstructor
public class AnalyticsQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    // Appends WHERE fragments shared across all 5 pages, only for filters that are populated.
    // Uses "col IN (:param)" rather than "col = ANY(:param)" — Spring's NamedParameterJdbcTemplate
    // auto-expands a bound Collection into an IN-list for the IN(...) form (a standard, well-tested
    // mechanism); ANY(:array) would require manually building a java.sql.Array, which adds
    // complexity for no benefit here. Callers must never invoke this with an empty-but-non-null
    // Collection filter field — isNotEmpty() guards against Spring's "cannot expand empty list"
    // error, so callers just need to leave unused filters null.
    void appendCommonFilters(StringBuilder sql, MapSqlParameterSource params, AnalyticsFilterRequest f) {
        if (isNotEmpty(f.getDates())) {
            sql.append(" AND work_date IN (:dates)");
            params.addValue("dates", f.getDates());
        }
        if (f.getDateFrom() != null) {
            sql.append(" AND work_date >= :dateFrom");
            params.addValue("dateFrom", f.getDateFrom());
        }
        if (f.getDateTo() != null) {
            sql.append(" AND work_date <= :dateTo");
            params.addValue("dateTo", f.getDateTo());
        }
        if (isNotEmpty(f.getMonths())) {
            sql.append(" AND month_start IN (:months)");
            params.addValue("months", f.getMonths());
        }
        if (isNotEmpty(f.getShiftIds())) {
            sql.append(" AND shift_type_id IN (:shiftIds)");
            params.addValue("shiftIds", f.getShiftIds());
        }
        if (isNotEmpty(f.getProductionOrderIds())) {
            sql.append(" AND production_order_id IN (:productionOrderIds)");
            params.addValue("productionOrderIds", f.getProductionOrderIds());
        }
        if (isNotEmpty(f.getNotes())) {
            sql.append(" AND note IN (:notes)");
            params.addValue("notes", f.getNotes());
        }
        if (f.getNoteLike() != null && !f.getNoteLike().isBlank()) {
            sql.append(" AND note ILIKE :noteLike");
            params.addValue("noteLike", "%" + f.getNoteLike() + "%");
        }
        if (isNotEmpty(f.getStartTimes())) {
            sql.append(" AND operation_start_time IN (:startTimes)");
            params.addValue("startTimes", f.getStartTimes());
        }
        if (f.getStartTimeFrom() != null) {
            sql.append(" AND operation_start_time >= :startTimeFrom");
            params.addValue("startTimeFrom", f.getStartTimeFrom());
        }
        if (f.getStartTimeTo() != null) {
            sql.append(" AND operation_start_time <= :startTimeTo");
            params.addValue("startTimeTo", f.getStartTimeTo());
        }
        if (isNotEmpty(f.getProductIds())) {
            sql.append(" AND product_id IN (:productIds)");
            params.addValue("productIds", f.getProductIds());
        }
        if (isNotEmpty(f.getOperationIds())) {
            sql.append(" AND operation_id IN (:operationIds)");
            params.addValue("operationIds", f.getOperationIds());
        }
        if (isNotEmpty(f.getEmployeeIds())) {
            sql.append(" AND employee_id IN (:employeeIds)");
            params.addValue("employeeIds", f.getEmployeeIds());
        }
        if (f.getDurationMinFrom() != null) {
            sql.append(" AND duration_min >= :durationMinFrom");
            params.addValue("durationMinFrom", f.getDurationMinFrom());
        }
        if (f.getDurationMinTo() != null) {
            sql.append(" AND duration_min <= :durationMinTo");
            params.addValue("durationMinTo", f.getDurationMinTo());
        }
    }

    private boolean isNotEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    // Backs the "napomena" multi-select filter option lists on the analytics pages.
    public List<String> findDistinctNotes() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT note FROM work_log_facts WHERE note IS NOT NULL ORDER BY note",
                String.class);
    }

    // Backs the product/operation/employee multi-select filter option lists.
    public List<AnalyticsOptionDto> findDistinctProducts() {
        return jdbc.getJdbcTemplate().query(
                "SELECT DISTINCT product_id, product_name FROM work_log_facts ORDER BY product_name",
                (rs, rowNum) -> new AnalyticsOptionDto(rs.getLong("product_id"), rs.getString("product_name")));
    }

    public List<AnalyticsOptionDto> findDistinctOperations() {
        return jdbc.getJdbcTemplate().query(
                "SELECT DISTINCT operation_id, operation_name FROM work_log_facts ORDER BY operation_name",
                (rs, rowNum) -> new AnalyticsOptionDto(rs.getLong("operation_id"), rs.getString("operation_name")));
    }

    public List<AnalyticsOptionDto> findDistinctEmployees() {
        return jdbc.getJdbcTemplate().query("""
                SELECT DISTINCT f.employee_id, e.full_name
                FROM work_log_facts f JOIN employees e ON e.id = f.employee_id
                ORDER BY e.full_name
                """,
                (rs, rowNum) -> new AnalyticsOptionDto(rs.getLong("employee_id"), rs.getString("full_name")));
    }

    // Shared by page 1 (Proizvod-operacija) and page 4 (Efikasnost proizvoda) — both pages
    // have the identical filter set and output shape per the spec, so a single query serves
    // both; AnalyticsController exposes them as two endpoints for a clearer frontend route
    // per page. Reads: dates/dateRange/months/shiftIds/productionOrderIds/notes/noteLike/
    // startTimes/startTimeRange from AnalyticsFilterRequest (all via appendCommonFilters);
    // ignores productIds/operationIds/employeeIds (not part of either page's filter set).
    public List<ProductOperationSummaryDto> findProductOperationSummary(AnalyticsFilterRequest filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    product_id, product_name, operation_id, operation_name,
                    SUM(quantity) AS sum_quantity,
                    SUM(scrap) AS sum_scrap,
                    SUM(duration_min) AS sum_duration_min,
                    SUM(quantity) / NULLIF(SUM(duration_min) / 60.0, 0) AS avg_per_hour,
                    SUM(scrap)::numeric / NULLIF(SUM(quantity) + SUM(scrap), 0) * 100 AS defect_pct,
                    SUM(approved_performance_rate * duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL)
                        / NULLIF(SUM(duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL), 0) AS avg_performance_pct,
                    SUM(approved_performance_rate * duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL) AS sum_weighted_performance,
                    SUM(duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL) AS sum_performance_duration_min
                FROM work_log_facts
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendCommonFilters(sql, params, filter);
        sql.append(" GROUP BY product_id, product_name, operation_id, operation_name");
        sql.append(" ORDER BY product_name, operation_name");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new ProductOperationSummaryDto(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getLong("operation_id"),
                rs.getString("operation_name"),
                rs.getLong("sum_quantity"),
                rs.getLong("sum_scrap"),
                rs.getLong("sum_duration_min"),
                rs.getBigDecimal("avg_per_hour"),
                rs.getBigDecimal("defect_pct"),
                rs.getBigDecimal("avg_performance_pct"),
                rs.getBigDecimal("sum_weighted_performance"),
                (Long) rs.getObject("sum_performance_duration_min")
        ));
    }

    // Page 3 — Efikasnost radnika. Flat, grouped by employee only. Reads: dates/dateRange/
    // months/shiftIds/productionOrderIds/notes/noteLike/productIds/operationIds. Ignores
    // startTimes/startTimeRange/employeeIds (not part of this page's filter set).
    public List<EmployeeEfficiencyDto> findEmployeeEfficiency(AnalyticsFilterRequest filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.employee_id AS employee_id, e.full_name AS employee_name,
                    SUM(f.approved_performance_rate * f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL)
                        / NULLIF(SUM(f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL), 0) AS avg_performance_pct,
                    SUM(f.scrap)::numeric / NULLIF(SUM(f.quantity) + SUM(f.scrap), 0) * 100 AS defect_pct,
                    SUM(f.quantity) AS sum_quantity,
                    SUM(f.scrap) AS sum_scrap
                FROM work_log_facts f
                JOIN employees e ON e.id = f.employee_id
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendCommonFilters(sql, params, filter);
        sql.append(" GROUP BY f.employee_id, e.full_name");
        sql.append(" ORDER BY e.full_name");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new EmployeeEfficiencyDto(
                rs.getLong("employee_id"),
                rs.getString("employee_name"),
                rs.getBigDecimal("avg_performance_pct"),
                rs.getBigDecimal("defect_pct"),
                rs.getLong("sum_quantity"),
                rs.getLong("sum_scrap")
        ));
    }

    // Page 5 — Efikasnost operacija - količina. Flat, grouped by operation only. Reads:
    // dates/dateRange/months/shiftIds/productionOrderIds/notes/noteLike/startTimes/
    // startTimeRange/productIds. Ignores operationIds/employeeIds.
    public List<OperationEfficiencyDto> findOperationEfficiency(AnalyticsFilterRequest filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    operation_id, operation_name,
                    SUM(approved_performance_rate * duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL)
                        / NULLIF(SUM(duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL), 0) AS avg_performance_pct,
                    SUM(scrap)::numeric / NULLIF(SUM(quantity) + SUM(scrap), 0) * 100 AS defect_pct,
                    SUM(quantity) / NULLIF(SUM(duration_min) / 60.0, 0) AS avg_per_hour,
                    SUM(quantity) AS sum_quantity,
                    SUM(scrap) AS sum_scrap
                FROM work_log_facts
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendCommonFilters(sql, params, filter);
        sql.append(" GROUP BY operation_id, operation_name");
        sql.append(" ORDER BY operation_name");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new OperationEfficiencyDto(
                rs.getLong("operation_id"),
                rs.getString("operation_name"),
                rs.getBigDecimal("avg_performance_pct"),
                rs.getBigDecimal("defect_pct"),
                rs.getBigDecimal("avg_per_hour"),
                rs.getLong("sum_quantity"),
                rs.getLong("sum_scrap")
        ));
    }

    // Page 2 — Proizvod-datum-operacija-radnik. Always pre-aggregates to (date, shift,
    // product, operation, employee) grain — this is the ONLY query that joins employees for
    // display purposes (employee name is not denormalized onto work_log_facts, since page 2
    // is the only page that needs it, per the fact-table design). Reads: dateRange/months/
    // productionOrderIds/notes/noteLike/startTimes/startTimeRange (top filter panel) plus
    // employeeIds/productIds/operationIds/durationMinFrom/durationMinTo (sidebar). Ignores
    // dates/shiftIds (not part of this page's filter set — shift is a grouping dimension here,
    // not a top-level filter, per the spec).
    public List<ProductDateOperationEmployeeDto> findProductDateOperationEmployeeSummary(AnalyticsFilterRequest filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.work_date AS work_date, f.shift_type_id AS shift_type_id, f.shift_code AS shift_code,
                    f.product_id AS product_id, f.product_name AS product_name,
                    f.operation_id AS operation_id, f.operation_name AS operation_name,
                    f.employee_id AS employee_id, e.full_name AS employee_name,
                    SUM(f.quantity) AS sum_quantity,
                    SUM(f.scrap) AS sum_scrap,
                    SUM(f.duration_min) AS sum_duration_min,
                    SUM(f.quantity) / NULLIF(SUM(f.duration_min) / 60.0, 0) AS avg_per_hour,
                    SUM(f.scrap)::numeric / NULLIF(SUM(f.quantity) + SUM(f.scrap), 0) * 100 AS defect_pct,
                    SUM(f.approved_performance_rate * f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL)
                        / NULLIF(SUM(f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL), 0) AS avg_performance_pct,
                    SUM(f.approved_performance_rate * f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL) AS sum_weighted_performance,
                    SUM(f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL) AS sum_performance_duration_min
                FROM work_log_facts f
                JOIN employees e ON e.id = f.employee_id
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendCommonFilters(sql, params, filter);
        sql.append(" GROUP BY f.work_date, f.shift_type_id, f.shift_code, f.product_id, f.product_name,");
        sql.append(" f.operation_id, f.operation_name, f.employee_id, e.full_name");
        sql.append(" ORDER BY f.work_date, f.shift_code, f.product_name, f.operation_name, e.full_name");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new ProductDateOperationEmployeeDto(
                rs.getObject("work_date", java.time.LocalDate.class),
                rs.getLong("shift_type_id"),
                rs.getString("shift_code"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getLong("operation_id"),
                rs.getString("operation_name"),
                rs.getLong("employee_id"),
                rs.getString("employee_name"),
                rs.getLong("sum_quantity"),
                rs.getLong("sum_scrap"),
                rs.getLong("sum_duration_min"),
                rs.getBigDecimal("avg_per_hour"),
                rs.getBigDecimal("defect_pct"),
                rs.getBigDecimal("avg_performance_pct"),
                rs.getBigDecimal("sum_weighted_performance"),
                (Long) rs.getObject("sum_performance_duration_min")
        ));
    }
}
