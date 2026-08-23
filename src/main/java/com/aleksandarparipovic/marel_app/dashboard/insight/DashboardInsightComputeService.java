package com.aleksandarparipovic.marel_app.dashboard.insight;

import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.MissingEntryRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NoNormRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.NormFitRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.OperationVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.PerformerRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ProductVolumeRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.ScrapRow;
import com.aleksandarparipovic.marel_app.dashboard.insight.dto.InsightRows.SpreadRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Computes every insight of the daily snapshot and stores it.
 *
 * <p>All of it reads {@code work_log_facts}, the denormalised analytics table the
 * recalculation engine already maintains — never the live {@code work_logs}. Two
 * reasons: the fact table is what the analytics screens answer from, so a card
 * and a report cannot disagree; and it already carries the product and operation
 * names, so a month of work aggregates without joining half the schema.
 *
 * <h2>On the thresholds</h2>
 * The numbers below decide what counts as "worth looking at". They are NOT
 * business rules — nothing is paid or calculated from them, they only choose
 * which rows reach a card. They are gathered here, named, and safe to change:
 * raising {@link #NORM_DEVIATION_PP} shortens the norm cards, it does not change
 * anybody's performance. Any of them can become a setting the day somebody wants
 * to tune it from the screen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardInsightComputeService {

    /** How far back the month-scale questions look. */
    public static final int WINDOW_DAYS = 30;

    /** Rows kept per card. The board is scanned, not read. */
    private static final int ROWS = 6;

    /** Rows kept for the data-gap card, which is a worklist rather than a highlight. */
    private static final int GAP_ROWS = 12;

    /** How far a rate must sit from 100 % before the norm is called into question. */
    private static final int NORM_DEVIATION_PP = 15;

    /** Below this much recorded work, a percentage says more about luck than about the norm. */
    private static final int NORM_MIN_MINUTES = 600;
    private static final int NORM_MIN_LOGS = 5;

    /** What makes an un-normed operation worth naming. */
    private static final int NO_NORM_MIN_QUANTITY = 200;

    /** A performer is ranked only once they have a month's worth of hours behind them. */
    private static final int PERFORMER_MIN_MINUTES = 1_200;

    /** Spread needs several people, each with enough time on the operation to compare. */
    private static final int SPREAD_MIN_EMPLOYEES = 3;
    private static final int SPREAD_MIN_EMPLOYEE_MINUTES = 120;
    private static final int SPREAD_MIN_PP = 40;

    /** Scrap is compared with the same operation's own earlier level. */
    private static final int SCRAP_BASELINE_DAYS = 180;
    private static final int SCRAP_MIN_PIECES = 20;
    private static final int SCRAP_MIN_BASELINE_PIECES = 200;
    private static final int SCRAP_MIN_DELTA_PP = 3;

    /** Snapshots older than this are dropped; the board's trend never looks further back. */
    private static final int RETENTION_DAYS = 120;

    private final NamedParameterJdbcTemplate jdbc;
    private final DashboardInsightRepository repository;

    /**
     * Answers every question for one day and stores the results.
     *
     * <p>{@code computedFor} is the day the snapshot describes, and the window ends
     * on it. The job passes today, so the window is the last 30 days up to and
     * including today's partial data — the same thing the analytics page would
     * show if it were opened at that moment.
     */
    @Transactional
    public void computeFor(LocalDate computedFor) {
        LocalDate from = computedFor.minusDays(WINDOW_DAYS - 1L);
        LocalDate yesterday = computedFor.minusDays(1);

        repository.save(DashboardInsightKey.NORM_TOO_LOW, computedFor, WINDOW_DAYS,
                normFit(from, computedFor, true));
        repository.save(DashboardInsightKey.NORM_TOO_HIGH, computedFor, WINDOW_DAYS,
                normFit(from, computedFor, false));
        repository.save(DashboardInsightKey.NO_NORM_HIGH_VOLUME, computedFor, WINDOW_DAYS,
                noNormHighVolume(from, computedFor));
        repository.save(DashboardInsightKey.MOST_WORKED_OPERATIONS, computedFor, WINDOW_DAYS,
                operationVolume(from, computedFor, true));
        repository.save(DashboardInsightKey.LEAST_WORKED_OPERATIONS, computedFor, WINDOW_DAYS,
                operationVolume(from, computedFor, false));
        repository.save(DashboardInsightKey.YESTERDAY_TOP_OPERATIONS, computedFor, 1,
                operationVolume(yesterday, yesterday, true));
        repository.save(DashboardInsightKey.YESTERDAY_TOP_PRODUCTS, computedFor, 1,
                productVolume(yesterday, yesterday));
        repository.save(DashboardInsightKey.TOP_PERFORMERS, computedFor, WINDOW_DAYS,
                topPerformers(from, computedFor));
        repository.save(DashboardInsightKey.MISSING_ENTRIES, computedFor, WINDOW_DAYS,
                missingEntries(from, computedFor));
        repository.save(DashboardInsightKey.PERFORMANCE_SPREAD, computedFor, WINDOW_DAYS,
                performanceSpread(from, computedFor));
        repository.save(DashboardInsightKey.SCRAP_SPIKE, computedFor, WINDOW_DAYS,
                scrapSpike(from, computedFor));

        int removed = repository.deleteComputedBefore(computedFor.minusDays(RETENTION_DAYS));
        if (removed > 0) {
            log.info("[DashboardInsight] Uklonjeno {} starih snimaka.", removed);
        }
    }

    // ---------------------------------------------------------------- norm fit

    /**
     * Operations whose measured output has drifted away from the norm in force.
     *
     * <p>The rate is {@code (pieces per hour) / min_norm}, the same comparison
     * {@code WorkLogPerformanceCalculator} makes per log, but aggregated over the
     * window and — importantly — NOT capped at {@code max_efficiency_percent}. The
     * paid rate is capped, and a ceiling is precisely what would hide the case this
     * card exists to find: a norm set so low that everybody sits on the ceiling.
     *
     * @param above true for norms that look too easy, false for too hard
     */
    private List<NormFitRow> normFit(LocalDate from, LocalDate to, boolean above) {
        String sql = """
                SELECT * FROM (
                    SELECT f.operation_id,
                           max(f.operation_name)                          AS operation_name,
                           max(f.product_id)                              AS product_id,
                           max(f.product_name)                            AS product_name,
                           o.min_norm                                     AS min_norm,
                           o.max_norm                                     AS max_norm,
                           sum(f.quantity)::bigint                        AS quantity,
                           sum(f.duration_min)::bigint                    AS duration_min,
                           count(DISTINCT f.employee_id)::int             AS employee_count,
                           count(*)::int                                  AS log_count,
                           round(100.0 * (sum(f.quantity)::numeric * 60)
                                 / nullif(sum(f.duration_min), 0)
                                 / nullif(o.min_norm, 0), 1)              AS rate_pct
                    FROM work_log_facts f
                    JOIN operations o ON o.id = f.operation_id
                    WHERE f.work_date BETWEEN :from AND :to
                      AND o.is_active = true
                      AND o.archived_at IS NULL
                      AND o.norm_required = true
                      AND o.min_norm > 0
                    GROUP BY f.operation_id, o.min_norm, o.max_norm
                    HAVING sum(f.duration_min) >= :minMinutes
                       AND count(*) >= :minLogs
                       AND sum(f.quantity) > 0
                ) fit
                WHERE rate_pct IS NOT NULL
                  AND %s
                ORDER BY %s
                LIMIT :limit
                """.formatted(
                above ? "rate_pct >= :threshold" : "rate_pct <= :threshold",
                above ? "rate_pct DESC" : "rate_pct ASC");

        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("minMinutes", NORM_MIN_MINUTES)
                        .addValue("minLogs", NORM_MIN_LOGS)
                        .addValue("threshold", above ? 100 + NORM_DEVIATION_PP : 100 - NORM_DEVIATION_PP)
                        .addValue("limit", ROWS),
                (rs, i) -> new NormFitRow(
                        rs.getLong("operation_id"),
                        rs.getString("operation_name"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        integer(rs, "min_norm"),
                        integer(rs, "max_norm"),
                        rs.getBigDecimal("rate_pct"),
                        rs.getLong("quantity"),
                        rs.getLong("duration_min"),
                        rs.getInt("employee_count"),
                        rs.getInt("log_count")));
    }

    /**
     * Operations worked without a norm.
     *
     * <p>{@code norm_required = false} is the whole definition, and the schema
     * guarantees it is the right one: a check constraint already forbids
     * {@code norm_required = true} without a min and max norm, so "has no norm" and
     * "does not require one" are the same set of rows. Every log on them is credited
     * a flat 100 %.
     */
    private List<NoNormRow> noNormHighVolume(LocalDate from, LocalDate to) {
        String sql = """
                SELECT f.operation_id,
                       max(f.operation_name)              AS operation_name,
                       max(f.product_id)                  AS product_id,
                       max(f.product_name)                AS product_name,
                       sum(f.quantity)::bigint            AS quantity,
                       sum(f.duration_min)::bigint        AS duration_min,
                       count(DISTINCT f.employee_id)::int AS employee_count,
                       max(f.work_date)                   AS last_worked_on
                FROM work_log_facts f
                JOIN operations o ON o.id = f.operation_id
                WHERE f.work_date BETWEEN :from AND :to
                  AND o.is_active = true
                  AND o.archived_at IS NULL
                  AND o.norm_required = false
                GROUP BY f.operation_id
                HAVING sum(f.quantity) >= :minQuantity
                ORDER BY sum(f.quantity) DESC
                LIMIT :limit
                """;

        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("minQuantity", NO_NORM_MIN_QUANTITY)
                        .addValue("limit", ROWS),
                (rs, i) -> new NoNormRow(
                        rs.getLong("operation_id"),
                        rs.getString("operation_name"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getLong("quantity"),
                        rs.getLong("duration_min"),
                        rs.getInt("employee_count"),
                        rs.getObject("last_worked_on", LocalDate.class)));
    }

    // ----------------------------------------------------------------- volume

    /** Most or least worked operations, ranked by the minutes spent on them. */
    private List<OperationVolumeRow> operationVolume(LocalDate from, LocalDate to, boolean most) {
        String sql = """
                SELECT f.operation_id,
                       max(f.operation_name)              AS operation_name,
                       max(f.product_id)                  AS product_id,
                       max(f.product_name)                AS product_name,
                       sum(f.quantity)::bigint            AS quantity,
                       sum(f.duration_min)::bigint        AS duration_min,
                       count(DISTINCT f.employee_id)::int AS employee_count
                FROM work_log_facts f
                WHERE f.work_date BETWEEN :from AND :to
                GROUP BY f.operation_id
                HAVING sum(f.duration_min) > 0
                ORDER BY sum(f.duration_min) %s
                LIMIT :limit
                """.formatted(most ? "DESC" : "ASC");

        return jdbc.query(sql, params(from, to), operationVolumeMapper());
    }

    /** Products by what was made of them. */
    private List<ProductVolumeRow> productVolume(LocalDate from, LocalDate to) {
        String sql = """
                SELECT f.product_id,
                       max(f.product_name)                 AS product_name,
                       sum(f.quantity)::bigint             AS quantity,
                       sum(f.duration_min)::bigint         AS duration_min,
                       count(DISTINCT f.operation_id)::int AS operation_count,
                       count(DISTINCT f.employee_id)::int  AS employee_count
                FROM work_log_facts f
                WHERE f.work_date BETWEEN :from AND :to
                GROUP BY f.product_id
                HAVING sum(f.quantity) > 0
                ORDER BY sum(f.quantity) DESC
                LIMIT :limit
                """;

        return jdbc.query(sql, params(from, to), (rs, i) -> new ProductVolumeRow(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getLong("quantity"),
                rs.getLong("duration_min"),
                rs.getInt("operation_count"),
                rs.getInt("employee_count")));
    }

    // ------------------------------------------------------------- performers

    /**
     * The employees holding the highest sustained rate over the window.
     *
     * <p>Ranked on the uncapped rate and weighted by minutes, so a single fast hour
     * cannot outrank a month of steady work, and the efficiency ceiling cannot
     * flatten everybody onto the same figure. The approved rate travels beside it,
     * because that is the one that reaches a payslip.
     *
     * <p>Only normed operations count: an operation with no norm credits 100 % by
     * definition, and letting those in would rank whoever happened to be assigned
     * to them.
     */
    private List<PerformerRow> topPerformers(LocalDate from, LocalDate to) {
        String sql = """
                SELECT f.employee_id,
                       max(e.full_name)                     AS employee_name,
                       max(e.employee_no)                   AS employee_no,
                       round(sum((f.quantity::numeric * 60 / nullif(f.duration_min, 0))
                                 / nullif(o.min_norm, 0) * 100 * f.duration_min)
                             / nullif(sum(f.duration_min), 0), 1)                   AS rate_pct,
                       round(sum(coalesce(f.approved_performance_rate, 0) * f.duration_min)
                             / nullif(sum(f.duration_min), 0), 1)                   AS approved_rate_pct,
                       sum(f.duration_min)::bigint          AS duration_min,
                       count(DISTINCT f.work_date)::int     AS day_count,
                       count(DISTINCT f.operation_id)::int  AS operation_count
                FROM work_log_facts f
                JOIN employees e  ON e.id = f.employee_id
                JOIN operations o ON o.id = f.operation_id
                WHERE f.work_date BETWEEN :from AND :to
                  AND o.norm_required = true
                  AND o.min_norm > 0
                  AND f.duration_min > 0
                GROUP BY f.employee_id
                HAVING sum(f.duration_min) >= :minMinutes
                ORDER BY rate_pct DESC NULLS LAST
                LIMIT :limit
                """;

        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("minMinutes", PERFORMER_MIN_MINUTES)
                        .addValue("limit", ROWS),
                (rs, i) -> new PerformerRow(
                        rs.getLong("employee_id"),
                        rs.getString("employee_name"),
                        rs.getString("employee_no"),
                        rs.getBigDecimal("rate_pct"),
                        rs.getBigDecimal("approved_rate_pct"),
                        rs.getLong("duration_min"),
                        rs.getInt("day_count"),
                        rs.getInt("operation_count")));
    }

    // ------------------------------------------------------------- data gaps

    /**
     * Shifts holding neither work nor an absence.
     *
     * <p>Read from {@code work_shifts} and not from the fact table, for the obvious
     * reason: the fact table only knows about shifts that HAVE work, and this asks
     * about the ones that do not.
     */
    private List<MissingEntryRow> missingEntries(LocalDate from, LocalDate to) {
        String sql = """
                SELECT ws.id                AS work_shift_id,
                       ws.employee_id       AS employee_id,
                       e.full_name          AS employee_name,
                       ws.work_date         AS work_date,
                       s.shift_code         AS shift_code,
                       ws.total_minutes     AS shift_minutes
                FROM work_shifts ws
                JOIN employees e ON e.id = ws.employee_id
                LEFT JOIN shifts s ON s.id = ws.shift_id
                WHERE ws.work_date BETWEEN :from AND :to
                  AND ws.is_active = true
                  AND ws.archived_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM work_logs wl
                      WHERE wl.work_shift_id = ws.id AND wl.is_active = true)
                  AND NOT EXISTS (
                      SELECT 1 FROM absence_records ar
                      WHERE ar.work_shift_id = ws.id AND ar.is_active = true)
                ORDER BY ws.work_date DESC, e.full_name ASC
                LIMIT :limit
                """;

        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("limit", GAP_ROWS),
                (rs, i) -> new MissingEntryRow(
                        rs.getLong("work_shift_id"),
                        rs.getLong("employee_id"),
                        rs.getString("employee_name"),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getString("shift_code"),
                        integer(rs, "shift_minutes")));
    }

    // ---------------------------------------------------------------- spread

    /**
     * Operations on which employees' results are far apart.
     *
     * <p>Each employee's own sustained rate on the operation is computed first, and
     * only then compared — an operation is flagged on the gap between people, never
     * on the gap between two logs of one person's day.
     */
    private List<SpreadRow> performanceSpread(LocalDate from, LocalDate to) {
        String sql = """
                WITH per_employee AS (
                    SELECT f.operation_id,
                           f.employee_id,
                           max(f.operation_name)      AS operation_name,
                           max(f.product_id)          AS product_id,
                           max(f.product_name)        AS product_name,
                           sum(f.duration_min)        AS duration_min,
                           round(100.0 * (sum(f.quantity)::numeric * 60)
                                 / nullif(sum(f.duration_min), 0)
                                 / nullif(o.min_norm, 0), 1) AS rate_pct
                    FROM work_log_facts f
                    JOIN operations o ON o.id = f.operation_id
                    WHERE f.work_date BETWEEN :from AND :to
                      AND o.is_active = true
                      AND o.archived_at IS NULL
                      AND o.norm_required = true
                      AND o.min_norm > 0
                    GROUP BY f.operation_id, f.employee_id, o.min_norm
                    HAVING sum(f.duration_min) >= :minEmployeeMinutes
                       AND sum(f.quantity) > 0
                )
                SELECT p.operation_id,
                       max(p.operation_name)                                 AS operation_name,
                       max(p.product_id)                                     AS product_id,
                       max(p.product_name)                                   AS product_name,
                       min(p.rate_pct)                                       AS lowest_pct,
                       max(p.rate_pct)                                       AS highest_pct,
                       max(p.rate_pct) - min(p.rate_pct)                     AS spread_pct,
                       count(*)::int                                         AS employee_count,
                       sum(p.duration_min)::bigint                           AS duration_min,
                       (array_agg(e.full_name ORDER BY p.rate_pct ASC))[1]   AS lowest_employee,
                       (array_agg(e.full_name ORDER BY p.rate_pct DESC))[1]  AS highest_employee
                FROM per_employee p
                JOIN employees e ON e.id = p.employee_id
                GROUP BY p.operation_id
                HAVING count(*) >= :minEmployees
                   AND max(p.rate_pct) - min(p.rate_pct) >= :minSpread
                ORDER BY max(p.rate_pct) - min(p.rate_pct) DESC
                LIMIT :limit
                """;

        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("minEmployeeMinutes", SPREAD_MIN_EMPLOYEE_MINUTES)
                        .addValue("minEmployees", SPREAD_MIN_EMPLOYEES)
                        .addValue("minSpread", SPREAD_MIN_PP)
                        .addValue("limit", ROWS),
                (rs, i) -> new SpreadRow(
                        rs.getLong("operation_id"),
                        rs.getString("operation_name"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("lowest_pct"),
                        rs.getBigDecimal("highest_pct"),
                        rs.getBigDecimal("spread_pct"),
                        rs.getString("lowest_employee"),
                        rs.getString("highest_employee"),
                        rs.getInt("employee_count"),
                        rs.getLong("duration_min")));
    }

    // ------------------------------------------------------------------ scrap

    /**
     * Operations scrapping more than they used to.
     *
     * <p>Each operation is compared with ITSELF over the preceding half year, never
     * with a factory-wide average: a difficult part that always scrapped 8 % is not
     * news, and a simple one that went from 0.5 % to 3 % is.
     */
    private List<ScrapRow> scrapSpike(LocalDate from, LocalDate to) {
        String sql = """
                WITH current AS (
                    SELECT f.operation_id,
                           max(f.operation_name)   AS operation_name,
                           max(f.product_id)       AS product_id,
                           max(f.product_name)     AS product_name,
                           sum(f.scrap)::bigint    AS scrap,
                           sum(f.quantity)::bigint AS quantity
                    FROM work_log_facts f
                    WHERE f.work_date BETWEEN :from AND :to
                    GROUP BY f.operation_id
                ),
                baseline AS (
                    SELECT f.operation_id,
                           sum(f.scrap)::bigint    AS scrap,
                           sum(f.quantity)::bigint AS quantity
                    FROM work_log_facts f
                    WHERE f.work_date BETWEEN :baselineFrom AND :baselineTo
                    GROUP BY f.operation_id
                ),
                compared AS (
                    SELECT c.operation_id, c.operation_name, c.product_id, c.product_name,
                           c.scrap, c.quantity,
                           round(100.0 * c.scrap / nullif(c.scrap + c.quantity, 0), 2) AS scrap_pct,
                           round(100.0 * b.scrap / nullif(b.scrap + b.quantity, 0), 2) AS baseline_pct
                    FROM current c
                    JOIN baseline b   ON b.operation_id = c.operation_id
                    JOIN operations o ON o.id = c.operation_id
                                     AND o.is_active = true
                                     AND o.archived_at IS NULL
                    WHERE c.scrap >= :minScrap
                      AND (b.scrap + b.quantity) >= :minBaselinePieces
                )
                SELECT *, scrap_pct - baseline_pct AS delta_pp
                FROM compared
                WHERE scrap_pct IS NOT NULL
                  AND baseline_pct IS NOT NULL
                  AND scrap_pct - baseline_pct >= :minDelta
                ORDER BY scrap_pct - baseline_pct DESC
                LIMIT :limit
                """;

        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("to", to)
                        .addValue("baselineFrom", from.minusDays(SCRAP_BASELINE_DAYS))
                        .addValue("baselineTo", from.minusDays(1))
                        .addValue("minScrap", SCRAP_MIN_PIECES)
                        .addValue("minBaselinePieces", SCRAP_MIN_BASELINE_PIECES)
                        .addValue("minDelta", SCRAP_MIN_DELTA_PP)
                        .addValue("limit", ROWS),
                (rs, i) -> new ScrapRow(
                        rs.getLong("operation_id"),
                        rs.getString("operation_name"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        rs.getLong("scrap"),
                        rs.getLong("quantity"),
                        rs.getBigDecimal("scrap_pct"),
                        rs.getBigDecimal("baseline_pct"),
                        rs.getBigDecimal("delta_pp")));
    }

    // ----------------------------------------------------------------- shared

    private MapSqlParameterSource params(LocalDate from, LocalDate to) {
        return new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("limit", ROWS);
    }

    private RowMapper<OperationVolumeRow> operationVolumeMapper() {
        return (rs, i) -> new OperationVolumeRow(
                rs.getLong("operation_id"),
                rs.getString("operation_name"),
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getLong("quantity"),
                rs.getLong("duration_min"),
                rs.getInt("employee_count"));
    }

    /** A nullable integer column, as null rather than as zero. */
    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
