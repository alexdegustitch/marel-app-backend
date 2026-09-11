package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.AbsenceRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.SupervisorDashboardResponse.MissingShiftRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.MissingEntryRow;
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
                  AND r.internal = false
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
        // `internal = false` exactly as the requests page filters (V42): a
        // supervisor's self-request lives on its order, and a badge counting
        // what the list will not show teaches people to distrust the badge.
        return count("SELECT COUNT(*) FROM manufacturing_time_requests WHERE status = :status AND internal = false",
                new MapSqlParameterSource("status", status));
    }

    // ── Who is on sick leave or vacation today ──────────────────────────────

    /**
     * Everybody on sick leave or godišnji odmor today.
     *
     * <p>Recognised by what the category IS — {@code type = 'SICK_LEAVE'}, which
     * V39 made a declared fact, plus the one non-sick leave GO — rather than by
     * the code list a setting used to carry. The setting predated V39, when the
     * schema could not answer "which categories mean sick leave"; now it can,
     * and a list somebody forgets to update is strictly worse than the schema.
     */
    private static final String LEAVE_CATEGORY_PREDICATE =
            "(upper(wcc.type) = 'SICK_LEAVE' OR wcc.category_no = 'GO')";

    public List<AbsenceRow> findAbsentOn(LocalDate day, LocalDate windowFrom, int limit) {
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
                        JOIN work_code_categories wcc ON wcc.id = ar2.work_code_category_id
                        WHERE ar2.employee_id = e.id
                          AND ar2.is_active = true
                          AND ws2.work_date BETWEEN :windowFrom AND :day
                          AND %s)::int AS days_in_window
                FROM absence_records ar
                JOIN work_shifts ws           ON ws.id = ar.work_shift_id
                JOIN employees e              ON e.id = ar.employee_id
                JOIN work_code_categories wcc ON wcc.id = ar.work_code_category_id
                WHERE ar.is_active = true
                  AND ws.work_date = :day
                  AND %s
                GROUP BY e.id, e.full_name, e.employee_no, wcc.category_no, wcc.category_name, ws.work_date
                ORDER BY e.full_name ASC
                LIMIT :limit
                """.formatted(LEAVE_CATEGORY_PREDICATE, LEAVE_CATEGORY_PREDICATE),
                new MapSqlParameterSource("day", day)
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

    public long countAbsentOn(LocalDate day) {
        return count("""
                SELECT COUNT(DISTINCT ar.employee_id)
                FROM absence_records ar
                JOIN work_shifts ws           ON ws.id = ar.work_shift_id
                JOIN work_code_categories wcc ON wcc.id = ar.work_code_category_id
                WHERE ar.is_active = true
                  AND ws.work_date = :day
                  AND %s
                """.formatted(LEAVE_CATEGORY_PREDICATE),
                new MapSqlParameterSource("day", day));
    }

    // ── Kartoni ready for payroll ────────────────────────────────────────────

    /**
     * How complete each employee's month is: how many REQUIRED days hold no
     * live shift.
     *
     * <p>A required day is what the work calendar calls a working day — an
     * explicit override wins, then WORKDAY, and with no row at all a weekday.
     * A plain Saturday is deliberately NOT required: the leave planner skips
     * Saturdays when it materialises sick leave, so demanding them would mark
     * a correctly-entered sick month as unfinished forever. The daily
     * missing-shifts list still nudges Saturdays; the month's readiness must
     * not punish them. Days outside the person's employment do not count.
     */
    public record RecordReadiness(Long employeeId, String fullName, Long employeeRecordId,
                                  Long monthlyReportId, int requiredDays, int missingDays) {}

    public List<RecordReadiness> findRecordReadiness(LocalDate from, LocalDate to) {
        return jdbc.query("""
                WITH required AS (
                    SELECT d::date AS day
                    FROM generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day') d
                    LEFT JOIN work_calendar_days wcd ON wcd.calendar_date = d::date
                    WHERE COALESCE(wcd.working_override,
                                   CASE WHEN wcd.day_type IS NOT NULL
                                        THEN wcd.day_type = 'WORKDAY'
                                        ELSE extract(isodow FROM d) NOT IN (6, 7) END)
                )
                SELECT e.id        AS employee_id,
                       e.full_name AS full_name,
                       er.id       AS employee_record_id,
                       max(mr.id)  AS monthly_report_id,
                       count(DISTINCT r.day)::int AS required_days,
                       count(DISTINCT r.day) FILTER (WHERE NOT EXISTS (
                           SELECT 1 FROM work_shifts ws
                           WHERE ws.employee_id = e.id
                             AND ws.work_date = r.day
                             AND ws.is_active = true
                             AND ws.archived_at IS NULL))::int AS missing_days
                FROM employees e
                LEFT JOIN employee_records er ON er.employee_id = e.id
                       AND er.archived_at IS NULL
                       AND er.start_date <= CAST(:to AS date)
                       AND er.end_date >= CAST(:from AS date)
                LEFT JOIN monthly_reports mr ON mr.employee_record_id = er.id
                JOIN required r ON r.day >= e.employment_start_date
                       AND (e.employment_end_date IS NULL OR r.day <= e.employment_end_date)
                WHERE e.is_active = true
                  AND e.archived_at IS NULL
                GROUP BY e.id, e.full_name, er.id
                ORDER BY e.full_name ASC, e.id ASC
                """,
                new MapSqlParameterSource("from", from).addValue("to", to),
                (rs, i) -> new RecordReadiness(
                        rs.getLong("employee_id"),
                        rs.getString("full_name"),
                        nullableLong(rs, "employee_record_id"),
                        nullableLong(rs, "monthly_report_id"),
                        rs.getInt("required_days"),
                        rs.getInt("missing_days")));
    }

    // ── Shifts that exist but hold nothing ──────────────────────────────────

    /**
     * Every live shift with neither work nor an absence on it — ALL of history,
     * not the snapshot's 30-day window. This is the karton-hygiene worklist the
     * board's "Rupe u unosu" tile counts: an empty shift from two months ago
     * still corrupts its month's payroll the day somebody recalculates it.
     */
    public List<MissingEntryRow> findEntryGaps(int limit) {
        return jdbc.query("""
                SELECT ws.id                 AS work_shift_id,
                       ws.employee_id        AS employee_id,
                       ws.employee_record_id AS employee_record_id,
                       e.full_name           AS employee_name,
                       ws.work_date          AS work_date,
                       s.shift_code          AS shift_code,
                       ws.total_minutes      AS shift_minutes
                FROM work_shifts ws
                JOIN employees e ON e.id = ws.employee_id
                LEFT JOIN shifts s ON s.id = ws.shift_id
                WHERE ws.is_active = true
                  AND ws.archived_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM work_logs wl
                                  WHERE wl.work_shift_id = ws.id AND wl.is_active = true)
                  AND NOT EXISTS (SELECT 1 FROM absence_records ar
                                  WHERE ar.work_shift_id = ws.id AND ar.is_active = true)
                ORDER BY ws.work_date DESC, e.full_name ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> new MissingEntryRow(
                        rs.getLong("work_shift_id"),
                        rs.getLong("employee_id"),
                        nullableLong(rs, "employee_record_id"),
                        rs.getString("employee_name"),
                        localDate(rs, "work_date"),
                        rs.getString("shift_code"),
                        nullableInt(rs, "shift_minutes")));
    }

    public long countEntryGaps() {
        return count("""
                SELECT COUNT(*)
                FROM work_shifts ws
                WHERE ws.is_active = true
                  AND ws.archived_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM work_logs wl
                                  WHERE wl.work_shift_id = ws.id AND wl.is_active = true)
                  AND NOT EXISTS (SELECT 1 FROM absence_records ar
                                  WHERE ar.work_shift_id = ws.id AND ar.is_active = true)
                """, new MapSqlParameterSource());
    }

    // ── Employees the day has no entry for ──────────────────────────────────

    /**
     * Who is employed on {@code day} and has no active shift on it.
     *
     * <p>An employee whose leave PERIOD covers the day is excluded even when the
     * shift itself has not been materialised yet — the absence is already
     * recorded, and offering to enter it again would create the very duplicate
     * the karton sweep exists to avoid.
     */
    public List<MissingShiftRow> findEmployeesWithoutShift(LocalDate day, int limit) {
        return jdbc.query("""
                SELECT e.id          AS employee_id,
                       e.full_name   AS full_name,
                       e.employee_no AS employee_no,
                       d.name        AS department_name,
                       er.id         AS employee_record_id
                FROM employees e
                LEFT JOIN departments d ON d.id = e.department_id
                LEFT JOIN employee_records er ON er.employee_id = e.id
                       AND er.archived_at IS NULL
                       AND :day BETWEEN er.start_date AND er.end_date
                WHERE e.is_active = true
                  AND e.archived_at IS NULL
                  AND (e.employment_start_date IS NULL OR e.employment_start_date <= :day)
                  AND (e.employment_end_date IS NULL OR e.employment_end_date >= :day)
                  AND NOT EXISTS (SELECT 1 FROM work_shifts ws
                                  WHERE ws.employee_id = e.id
                                    AND ws.work_date = :day
                                    AND ws.is_active = true
                                    AND ws.archived_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM employee_leave_periods lp
                                  WHERE lp.employee_id = e.id
                                    AND lp.archived_at IS NULL
                                    AND :day BETWEEN lp.date_from AND lp.date_to)
                ORDER BY e.full_name ASC, e.id ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("day", day).addValue("limit", limit),
                (rs, i) -> new MissingShiftRow(
                        rs.getLong("employee_id"),
                        rs.getString("full_name"),
                        rs.getString("employee_no"),
                        rs.getString("department_name"),
                        nullableLong(rs, "employee_record_id")));
    }

    public long countEmployeesWithoutShift(LocalDate day) {
        return count("""
                SELECT COUNT(*)
                FROM employees e
                WHERE e.is_active = true
                  AND e.archived_at IS NULL
                  AND (e.employment_start_date IS NULL OR e.employment_start_date <= :day)
                  AND (e.employment_end_date IS NULL OR e.employment_end_date >= :day)
                  AND NOT EXISTS (SELECT 1 FROM work_shifts ws
                                  WHERE ws.employee_id = e.id
                                    AND ws.work_date = :day
                                    AND ws.is_active = true
                                    AND ws.archived_at IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM employee_leave_periods lp
                                  WHERE lp.employee_id = e.id
                                    AND lp.archived_at IS NULL
                                    AND :day BETWEEN lp.date_from AND lp.date_to)
                """,
                new MapSqlParameterSource("day", day));
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
