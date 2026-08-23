package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.AbsenceRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.RecentPayrollRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.RecentRecordRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.RequestRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The live reads behind the supervisor's control board.
 *
 * <p>Its own class beside {@link DashboardQueryRepository} rather than more
 * methods inside it: these queries answer a different person's questions, and
 * several of them are scoped to the caller — which is a property of this board,
 * not of the administrator's.
 *
 * <p>Everything here is bounded by {@code :limit} or is a COUNT.
 */
@Repository
@RequiredArgsConstructor
public class SupervisorDashboardQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    // ── What this user was last working on ──────────────────────────────────

    /**
     * The kartoni this user last had open.
     *
     * <p>{@code employee_record_updates} already records exactly this — one row per
     * (karton, user) with the moment of last activity — so the board reads a trail
     * the application keeps anyway rather than inventing a second one.
     */
    public List<RecentRecordRow> findMyRecentRecords(Long userId, OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT er.id            AS employee_record_id,
                       e.id             AS employee_id,
                       e.full_name      AS employee_name,
                       er.start_date    AS period_start,
                       er.end_date      AS period_end,
                       eru.last_activity_at
                FROM employee_record_updates eru
                JOIN employee_records er ON er.id = eru.employee_record_id
                JOIN employees e         ON e.id = er.employee_id
                WHERE eru.user_id = :userId
                  AND eru.last_activity_at >= :since
                  AND er.archived_at IS NULL
                ORDER BY eru.last_activity_at DESC
                LIMIT :limit
                """,
                scoped(userId, since, limit),
                (rs, i) -> new RecentRecordRow(
                        rs.getLong("employee_record_id"),
                        rs.getLong("employee_id"),
                        rs.getString("employee_name"),
                        localDate(rs, "period_start"),
                        localDate(rs, "period_end"),
                        offsetDateTime(rs, "last_activity_at")));
    }

    public long countMyRecentRecords(Long userId, OffsetDateTime since) {
        return count("""
                SELECT COUNT(*)
                FROM employee_record_updates eru
                JOIN employee_records er ON er.id = eru.employee_record_id
                WHERE eru.user_id = :userId
                  AND eru.last_activity_at >= :since
                  AND er.archived_at IS NULL
                """, scoped(userId, since));
    }

    /** The payroll months this user last had open. Status only, never an amount. */
    public List<RecentPayrollRow> findMyRecentPayrolls(Long userId, OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT pri.id                AS payroll_run_item_id,
                       pri.monthly_report_id AS monthly_report_id,
                       e.id                  AS employee_id,
                       e.full_name  AS employee_name,
                       pri.period   AS period,
                       pri.status   AS status,
                       epriu.last_activity_at
                FROM employee_payroll_run_item_updates epriu
                JOIN payroll_run_items pri ON pri.id = epriu.payroll_run_item_id
                JOIN employees e           ON e.id = pri.employee_id
                WHERE epriu.user_id = :userId
                  AND epriu.last_activity_at >= :since
                  AND pri.archived_at IS NULL
                ORDER BY epriu.last_activity_at DESC
                LIMIT :limit
                """,
                scoped(userId, since, limit),
                (rs, i) -> new RecentPayrollRow(
                        rs.getLong("payroll_run_item_id"),
                        nullableLong(rs, "monthly_report_id"),
                        rs.getLong("employee_id"),
                        rs.getString("employee_name"),
                        localDate(rs, "period"),
                        rs.getString("status"),
                        offsetDateTime(rs, "last_activity_at")));
    }

    public long countMyRecentPayrolls(Long userId, OffsetDateTime since) {
        return count("""
                SELECT COUNT(*)
                FROM employee_payroll_run_item_updates epriu
                JOIN payroll_run_items pri ON pri.id = epriu.payroll_run_item_id
                WHERE epriu.user_id = :userId
                  AND epriu.last_activity_at >= :since
                  AND pri.archived_at IS NULL
                """, scoped(userId, since));
    }

    // ── Manufacturing-time requests still moving ────────────────────────────

    /**
     * Open requests in one status.
     *
     * <p>PENDING and IN_REVIEW are two cards because they are two different asks:
     * one is "nobody has taken this", the other is "somebody took it and it is not
     * done". Oldest first in both — the point of the card is what has waited.
     */
    public List<RequestRow> findOpenRequests(String status, Long currentUserId, int limit) {
        return jdbc.query("""
                SELECT r.id,
                       p.id                AS product_id,
                       p.product_name      AS product_name,
                       r.request_type      AS request_type,
                       r.status            AS status,
                       requester.full_name AS requested_by_name,
                       assignee.full_name  AS assigned_to_name,
                       r.assigned_to       AS assigned_to,
                       r.created_at        AS created_at
                FROM manufacturing_time_requests r
                JOIN products p       ON p.id = r.product_id
                LEFT JOIN users requester ON requester.id = r.created_by
                LEFT JOIN users assignee  ON assignee.id = r.assigned_to
                WHERE r.status = :status
                ORDER BY r.created_at ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("status", status).addValue("limit", limit),
                (rs, i) -> {
                    OffsetDateTime createdAt = offsetDateTime(rs, "created_at");
                    Long assignedTo = nullableLong(rs, "assigned_to");
                    return new RequestRow(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            rs.getString("product_name"),
                            rs.getString("request_type"),
                            rs.getString("status"),
                            rs.getString("requested_by_name"),
                            rs.getString("assigned_to_name"),
                            assignedTo != null && assignedTo.equals(currentUserId),
                            daysSince(createdAt),
                            createdAt);
                });
    }

    public long countOpenRequests(String status) {
        return count("SELECT COUNT(*) FROM manufacturing_time_requests WHERE status = :status",
                new MapSqlParameterSource("status", status));
    }

    // ── Absence on the codes that mean sick leave ───────────────────────────

    /**
     * Who is absent today on one of the configured codes.
     *
     * <p>The codes arrive as {@code category_no} values from a setting, because the
     * schema does not say which category means sick leave —
     * {@code work_code_categories.type} is free text. Matching on the code rather
     * than the id is on purpose: a category is re-versioned over time (valid_from /
     * valid_until) and every version keeps the code the factory knows it by.
     */
    public List<AbsenceRow> findAbsentOn(LocalDate day, List<String> categoryNos, LocalDate windowFrom, int limit) {
        return jdbc.query("""
                SELECT e.id                     AS employee_id,
                       e.full_name              AS employee_name,
                       e.employee_no            AS employee_no,
                       wcc.category_no          AS category_no,
                       wcc.category_name        AS category_name,
                       ws.work_date             AS work_date,
                       sum(ar.absence_minutes)::int AS absence_minutes,
                       (SELECT count(DISTINCT ws2.work_date)
                        FROM absence_records ar2
                        JOIN work_shifts ws2          ON ws2.id = ar2.work_shift_id
                        JOIN work_code_categories w2  ON w2.id = ar2.work_code_category_id
                        WHERE ar2.employee_id = e.id
                          AND ar2.is_active = true
                          AND ws2.work_date BETWEEN :windowFrom AND :day
                          AND w2.category_no IN (:categoryNos))::int AS days_in_window
                FROM absence_records ar
                JOIN work_shifts ws           ON ws.id = ar.work_shift_id
                JOIN employees e              ON e.id = ar.employee_id
                JOIN work_code_categories wcc ON wcc.id = ar.work_code_category_id
                WHERE ar.is_active = true
                  AND ws.work_date = :day
                  AND wcc.category_no IN (:categoryNos)
                GROUP BY e.id, e.full_name, e.employee_no, wcc.category_no, wcc.category_name, ws.work_date
                ORDER BY e.full_name ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("day", day)
                        .addValue("categoryNos", categoryNos)
                        .addValue("windowFrom", windowFrom)
                        .addValue("limit", limit),
                (rs, i) -> new AbsenceRow(
                        rs.getLong("employee_id"),
                        rs.getString("employee_name"),
                        rs.getString("employee_no"),
                        rs.getString("category_no"),
                        rs.getString("category_name"),
                        localDate(rs, "work_date"),
                        nullableInt(rs, "absence_minutes"),
                        nullableInt(rs, "days_in_window")));
    }

    public long countAbsentOn(LocalDate day, List<String> categoryNos) {
        return count("""
                SELECT COUNT(DISTINCT ar.employee_id)
                FROM absence_records ar
                JOIN work_shifts ws           ON ws.id = ar.work_shift_id
                JOIN work_code_categories wcc ON wcc.id = ar.work_code_category_id
                WHERE ar.is_active = true
                  AND ws.work_date = :day
                  AND wcc.category_no IN (:categoryNos)
                """,
                new MapSqlParameterSource("day", day).addValue("categoryNos", categoryNos));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private MapSqlParameterSource scoped(Long userId, OffsetDateTime since) {
        return new MapSqlParameterSource("userId", userId).addValue("since", since);
    }

    private MapSqlParameterSource scoped(Long userId, OffsetDateTime since, int limit) {
        return scoped(userId, since).addValue("limit", limit);
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private static long daysSince(OffsetDateTime moment) {
        if (moment == null) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(moment.toLocalDate(), LocalDate.now());
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private static OffsetDateTime offsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
