package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.dto.AdminDashboardResponse.NonWorkingDayRow;
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
                SELECT pri.id, pri.monthly_report_id, e.id AS employee_id, e.full_name,
                       pri.period, pri.updated_at
                FROM payroll_run_items pri
                JOIN employees e ON e.id = pri.employee_id
                WHERE pri.status = 'APPROVED'
                ORDER BY pri.period DESC NULLS LAST, pri.updated_at DESC NULLS LAST, pri.id DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> new ReadyPayrollRow(
                        rs.getLong("id"),
                        nullableLong(rs, "monthly_report_id"),
                        rs.getLong("employee_id"),
                        rs.getString("full_name"),
                        localDate(rs, "period"),
                        offsetDateTime(rs, "updated_at")));
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

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
