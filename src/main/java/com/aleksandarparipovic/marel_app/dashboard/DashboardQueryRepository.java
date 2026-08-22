package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NewOperationRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NewProductRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NewProductionOrderRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NewSampleOrderRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NewUserRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NonWorkingDayRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.OrderDeadlineRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.ReadyPayrollRow;
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
 * The reads behind the administrator's control board.
 *
 * <p>SQL rather than JPA, and one class rather than methods scattered over eight
 * repositories: every query here answers a question only the board asks ("the
 * five newest", "the nearest deadline across all deadlines of an order"), and
 * keeping them together means the board can change without touching the
 * repositories the rest of the application depends on.
 *
 * <p>Every list is bounded by {@code :limit} and every count is a COUNT — no
 * query here can return more rows as the database grows.
 */
@Repository
@RequiredArgsConstructor
public class DashboardQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    // ── Payroll handed over by the shop floor ───────────────────────────────

    public long countReadyPayrolls() {
        return count("SELECT COUNT(*) FROM payroll_run_items WHERE status = 'APPROVED'",
                new MapSqlParameterSource());
    }

    public List<ReadyPayrollRow> findReadyPayrolls(int limit) {
        return jdbc.query("""
                SELECT pri.id, e.id AS employee_id, e.full_name, pri.period, pri.updated_at
                FROM payroll_run_items pri
                JOIN employees e ON e.id = pri.employee_id
                WHERE pri.status = 'APPROVED'
                ORDER BY pri.period DESC NULLS LAST, pri.updated_at DESC NULLS LAST, pri.id DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> new ReadyPayrollRow(
                        rs.getLong("id"),
                        rs.getLong("employee_id"),
                        rs.getString("full_name"),
                        localDate(rs, "period"),
                        offsetDateTime(rs, "updated_at")));
    }

    // ── New in the window ───────────────────────────────────────────────────

    public long countUsersSince(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM users
                WHERE created_at >= :since AND archived_at IS NULL
                """, new MapSqlParameterSource("since", since));
    }

    public List<NewUserRow> findNewUsers(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT u.id, u.full_name, u.username, r.role_name, u.account_status, u.created_at
                FROM users u
                LEFT JOIN roles r ON r.id = u.role_id
                WHERE u.created_at >= :since AND u.archived_at IS NULL
                ORDER BY u.created_at DESC, u.id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new NewUserRow(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getString("username"),
                        rs.getString("role_name"),
                        rs.getString("account_status"),
                        offsetDateTime(rs, "created_at")));
    }

    public long countProductsSince(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM products
                WHERE created_at >= :since AND archived_at IS NULL
                """, new MapSqlParameterSource("since", since));
    }

    public List<NewProductRow> findNewProducts(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT id, product_name, product_code, created_at
                FROM products
                WHERE created_at >= :since AND archived_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new NewProductRow(
                        rs.getLong("id"),
                        rs.getString("product_name"),
                        rs.getString("product_code"),
                        offsetDateTime(rs, "created_at")));
    }

    public long countOperationsSince(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM operations
                WHERE created_at >= :since AND archived_at IS NULL
                """, new MapSqlParameterSource("since", since));
    }

    public List<NewOperationRow> findNewOperations(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT o.id, o.op_name, p.id AS product_id, p.product_name, o.created_at
                FROM operations o
                JOIN products p ON p.id = o.product_id
                WHERE o.created_at >= :since AND o.archived_at IS NULL
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new NewOperationRow(
                        rs.getLong("id"),
                        rs.getString("op_name"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        offsetDateTime(rs, "created_at")));
    }

    public long countProductionOrdersSince(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM production_orders
                WHERE created_at >= :since AND archived_at IS NULL
                """, new MapSqlParameterSource("since", since));
    }

    public List<NewProductionOrderRow> findNewProductionOrders(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT id, code, name, status, created_at
                FROM production_orders
                WHERE created_at >= :since AND archived_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new NewProductionOrderRow(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("status"),
                        offsetDateTime(rs, "created_at")));
    }

    public long countSampleOrdersSince(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM sample_orders
                WHERE created_at >= :since AND archived_at IS NULL
                """, new MapSqlParameterSource("since", since));
    }

    public List<NewSampleOrderRow> findNewSampleOrders(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT id, name, deadline_date, created_at
                FROM sample_orders
                WHERE created_at >= :since AND archived_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new NewSampleOrderRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        localDate(rs, "deadline_date"),
                        offsetDateTime(rs, "created_at")));
    }

    // ── Deadlines ───────────────────────────────────────────────────────────

    /**
     * Orders that still owe a delivery, nearest deadline first.
     *
     * <p>An order can carry several deadlines; the one that matters is the
     * earliest still-active one, so the row is built from MIN over them rather
     * than from whichever row the join happened to return.
     *
     * <p>Past dates are NOT filtered out. An order that is already late is the
     * most urgent thing on this list, and hiding it would make the board quieter
     * than the shop floor.
     */
    public List<OrderDeadlineRow> findNearestDeadlines(LocalDate today, int limit) {
        return jdbc.query("""
                SELECT po.id, po.code, po.name, po.is_high_priority, d.deadline_date_to
                FROM production_orders po
                JOIN (
                    SELECT production_order_id, MIN(deadline_date_to) AS deadline_date_to
                    FROM production_order_deadlines
                    WHERE is_active = true AND archived_at IS NULL
                    GROUP BY production_order_id
                ) d ON d.production_order_id = po.id
                WHERE po.status = 'CREATED' AND po.is_active = true AND po.archived_at IS NULL
                ORDER BY d.deadline_date_to ASC, po.id ASC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> {
                    LocalDate deadline = localDate(rs, "deadline_date_to");
                    return new OrderDeadlineRow(
                            rs.getLong("id"),
                            rs.getString("code"),
                            rs.getString("name"),
                            deadline,
                            deadline == null ? 0 : daysBetween(today, deadline),
                            rs.getBoolean("is_high_priority"));
                });
    }

    public long countOpenOrdersWithDeadline() {
        return count("""
                SELECT COUNT(DISTINCT po.id)
                FROM production_orders po
                JOIN production_order_deadlines d ON d.production_order_id = po.id
                WHERE po.status = 'CREATED' AND po.is_active = true AND po.archived_at IS NULL
                  AND d.is_active = true AND d.archived_at IS NULL
                """, new MapSqlParameterSource());
    }

    // ── Calendar ────────────────────────────────────────────────────────────

    /**
     * The next days nobody works.
     *
     * <p>Ordinary weekends are excluded: the calendar stores a row for every day
     * of the year, so listing every NON_WORKING day would fill the card with
     * Saturdays and bury the holiday it exists to announce. A weekday marked
     * NON_WORKING by hand is a decision somebody made and stays in.
     *
     * <p>{@code working_override} means the day was turned back into a working
     * one, so it is no longer a day off.
     */
    private static final String NON_WORKING_PREDICATE = """
            calendar_date >= :from
              AND day_type <> 'WORKDAY'
              AND COALESCE(working_override, false) = false
              AND (day_type IN ('HOLIDAY', 'COLLECTIVE_LEAVE')
                   OR EXTRACT(ISODOW FROM calendar_date) < 6)
            """;

    public List<NonWorkingDayRow> findUpcomingNonWorkingDays(LocalDate today, int limit) {
        return jdbc.query("""
                SELECT calendar_date, day_type, label
                FROM work_calendar_days
                WHERE %s
                ORDER BY calendar_date ASC
                LIMIT :limit
                """.formatted(NON_WORKING_PREDICATE),
                new MapSqlParameterSource("from", today).addValue("limit", limit),
                (rs, i) -> {
                    LocalDate date = localDate(rs, "calendar_date");
                    return new NonWorkingDayRow(
                            date,
                            rs.getString("day_type"),
                            rs.getString("label"),
                            date == null ? 0 : daysBetween(today, date));
                });
    }

    /** How many such days fall inside the horizon — the card's badge. */
    public long countNonWorkingDaysBetween(LocalDate today, LocalDate until) {
        return count("""
                SELECT COUNT(*) FROM work_calendar_days
                WHERE %s AND calendar_date <= :until
                """.formatted(NON_WORKING_PREDICATE),
                new MapSqlParameterSource("from", today).addValue("until", until));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private MapSqlParameterSource params(OffsetDateTime since, int limit) {
        return new MapSqlParameterSource("since", since).addValue("limit", limit);
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        return java.time.temporal.ChronoUnit.DAYS.between(from, to);
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static OffsetDateTime offsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }
}
