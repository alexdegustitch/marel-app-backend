package com.aleksandarparipovic.marel_app.analytics.repository;

import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsFilterRequest;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsOptionDto;
import com.aleksandarparipovic.marel_app.analytics.dto.AnalyticsPageDto;
import com.aleksandarparipovic.marel_app.analytics.dto.EmployeeProductOperationDto;
import com.aleksandarparipovic.marel_app.analytics.dto.NormBasisDto;
import com.aleksandarparipovic.marel_app.analytics.dto.NoteOccurrenceDto;
import com.aleksandarparipovic.marel_app.analytics.dto.OperationSummaryDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductDateOperationEmployeeDto;
import com.aleksandarparipovic.marel_app.analytics.dto.ProductOperationSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
    //
    // Every column is qualified with `f.` — the fact table's alias, which every
    // query now uses. Not decoration: since the aggregates join `operations` for
    // the label, an unqualified `product_id` matches BOTH work_log_facts and
    // operations.product_id, and PostgreSQL rejects the query as ambiguous.
    // Uses "col IN (:param)" rather than "col = ANY(:param)" — Spring's NamedParameterJdbcTemplate
    // auto-expands a bound Collection into an IN-list for the IN(...) form (a standard, well-tested
    // mechanism); ANY(:array) would require manually building a java.sql.Array, which adds
    // complexity for no benefit here. Callers must never invoke this with an empty-but-non-null
    // Collection filter field — isNotEmpty() guards against Spring's "cannot expand empty list"
    // error, so callers just need to leave unused filters null.
    void appendCommonFilters(StringBuilder sql, MapSqlParameterSource params, AnalyticsFilterRequest f) {
        if (isNotEmpty(f.getDates())) {
            sql.append(" AND f.work_date IN (:dates)");
            params.addValue("dates", f.getDates());
        }
        if (f.getDateFrom() != null) {
            sql.append(" AND f.work_date >= :dateFrom");
            params.addValue("dateFrom", f.getDateFrom());
        }
        if (f.getDateTo() != null) {
            sql.append(" AND f.work_date <= :dateTo");
            params.addValue("dateTo", f.getDateTo());
        }
        if (isNotEmpty(f.getMonths())) {
            sql.append(" AND f.month_start IN (:months)");
            params.addValue("months", f.getMonths());
        }
        if (isNotEmpty(f.getShiftIds())) {
            sql.append(" AND f.shift_type_id IN (:shiftIds)");
            params.addValue("shiftIds", f.getShiftIds());
        }
        if (isNotEmpty(f.getProductionOrderIds())) {
            sql.append(" AND f.production_order_id IN (:productionOrderIds)");
            params.addValue("productionOrderIds", f.getProductionOrderIds());
        }
        if (isNotEmpty(f.getNotes())) {
            sql.append(" AND f.note IN (:notes)");
            params.addValue("notes", f.getNotes());
        }
        if (f.getNoteLike() != null && !f.getNoteLike().isBlank()) {
            sql.append(" AND f.note ILIKE :noteLike");
            params.addValue("noteLike", "%" + f.getNoteLike() + "%");
        }
        if (isNotEmpty(f.getStartTimes())) {
            sql.append(" AND f.operation_start_time IN (:startTimes)");
            params.addValue("startTimes", f.getStartTimes());
        }
        if (f.getStartTimeFrom() != null) {
            sql.append(" AND f.operation_start_time >= :startTimeFrom");
            params.addValue("startTimeFrom", f.getStartTimeFrom());
        }
        if (f.getStartTimeTo() != null) {
            sql.append(" AND f.operation_start_time <= :startTimeTo");
            params.addValue("startTimeTo", f.getStartTimeTo());
        }
        if (isNotEmpty(f.getProductIds())) {
            sql.append(" AND f.product_id IN (:productIds)");
            params.addValue("productIds", f.getProductIds());
        }
        if (isNotEmpty(f.getOperationIds())) {
            sql.append(" AND f.operation_id IN (:operationIds)");
            params.addValue("operationIds", f.getOperationIds());
        }
        if (isNotEmpty(f.getEmployeeIds())) {
            sql.append(" AND f.employee_id IN (:employeeIds)");
            params.addValue("employeeIds", f.getEmployeeIds());
        }
        if (f.getDurationMinFrom() != null) {
            sql.append(" AND f.duration_min >= :durationMinFrom");
            params.addValue("durationMinFrom", f.getDurationMinFrom());
        }
        if (f.getDurationMinTo() != null) {
            sql.append(" AND f.duration_min <= :durationMinTo");
            params.addValue("durationMinTo", f.getDurationMinTo());
        }
    }

    private boolean isNotEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    /** "PRODUCT" totals a product across its operations; anything else keeps the operation grain. */
    private boolean isProductLevel(AnalyticsFilterRequest f) {
        return "PRODUCT".equalsIgnoreCase(f.getLevel());
    }

    // SQL for the five reported measures, written once so the HAVING bounds below can never
    // drift from the SELECT list they filter.
    private static final String AGG_QUANTITY = "SUM(f.quantity)";
    private static final String AGG_SCRAP = "SUM(f.scrap)";
    private static final String AGG_PER_HOUR = "SUM(f.quantity) / NULLIF(SUM(f.duration_min) / 60.0, 0)";
    private static final String AGG_PERFORMANCE_PCT =
            "SUM(f.approved_performance_rate * f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL)"
                    + " / NULLIF(SUM(f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL), 0)";
    private static final String AGG_DEFECT_PCT =
            "SUM(f.scrap)::numeric / NULLIF(SUM(f.quantity) + SUM(f.scrap), 0) * 100";

    /**
     * Appends the aggregate bounds as a HAVING clause — post-aggregation by definition.
     *
     * <p>A row whose measure is NULL (a product nobody has an approved performance rate for,
     * an average per hour with no recorded duration behind it) does not satisfy a bound and
     * drops out. That is the honest reading of "show me rows between X and Y": a row with no
     * value is not between them.
     */
    private void appendAggregateBounds(StringBuilder sql, MapSqlParameterSource params, AnalyticsFilterRequest f) {
        List<String> having = new ArrayList<>();
        addBound(having, params, AGG_QUANTITY, ">=", "minQuantity", f.getMinQuantity());
        addBound(having, params, AGG_QUANTITY, "<=", "maxQuantity", f.getMaxQuantity());
        addBound(having, params, AGG_SCRAP, ">=", "minScrap", f.getMinScrap());
        addBound(having, params, AGG_SCRAP, "<=", "maxScrap", f.getMaxScrap());
        addBound(having, params, AGG_PER_HOUR, ">=", "minAvgPerHour", f.getMinAvgPerHour());
        addBound(having, params, AGG_PER_HOUR, "<=", "maxAvgPerHour", f.getMaxAvgPerHour());
        addBound(having, params, AGG_PERFORMANCE_PCT, ">=", "minPerformancePct", f.getMinPerformancePct());
        addBound(having, params, AGG_PERFORMANCE_PCT, "<=", "maxPerformancePct", f.getMaxPerformancePct());
        addBound(having, params, AGG_DEFECT_PCT, ">=", "minDefectPct", f.getMinDefectPct());
        addBound(having, params, AGG_DEFECT_PCT, "<=", "maxDefectPct", f.getMaxDefectPct());

        if (!having.isEmpty()) {
            sql.append(" HAVING ").append(String.join(" AND ", having));
        }
    }

    private void addBound(List<String> having, MapSqlParameterSource params,
                          String expression, String operator, String paramName, Object value) {
        if (value == null) return;
        having.add(expression + " " + operator + " :" + paramName);
        params.addValue(paramName, value);
    }

    /**
     * The work logs behind a note search, newest first.
     *
     * <p>Reached from a summary row via "Detaljnije": the caller sends the same filter the
     * report is showing, narrowed to the one product (and, at operation grain, the one
     * operation) whose row was clicked. So this method needs no arguments of its own — the
     * narrowing is expressed in {@code productIds}/{@code operationIds}, exactly as the
     * report's own filters are.
     *
     * <p>Capped: a note like "a" over a busy year matches more rows than a panel can show,
     * and an uncapped answer would be the whole fact table over the wire.
     */
    public List<NoteOccurrenceDto> findNoteOccurrences(AnalyticsFilterRequest filter, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.work_log_id AS work_log_id,
                    f.work_date AS work_date,
                    f.shift_code AS shift_code,
                    e.full_name AS employee_name,
                    p.product_name AS product_name,
                    o.op_name AS operation_name,
                    f.operation_start_time AS start_time,
                    f.duration_min AS duration_min,
                    f.quantity AS quantity,
                    f.scrap AS scrap,
                    f.note AS note
                FROM work_log_facts f
                JOIN employees e ON e.id = f.employee_id
                JOIN products p ON p.id = f.product_id
                JOIN operations o ON o.id = f.operation_id
                WHERE f.note IS NOT NULL
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendCommonFilters(sql, params, filter);
        sql.append(" ORDER BY f.work_date DESC, f.operation_start_time DESC LIMIT :limit");
        params.addValue("limit", limit);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new NoteOccurrenceDto(
                rs.getLong("work_log_id"),
                rs.getObject("work_date", java.time.LocalDate.class),
                rs.getString("shift_code"),
                rs.getString("employee_name"),
                rs.getString("product_name"),
                rs.getString("operation_name"),
                rs.getObject("start_time", java.time.LocalTime.class),
                rs.getInt("duration_min"),
                rs.getLong("quantity"),
                rs.getLong("scrap"),
                rs.getString("note")
        ));
    }

    // Backs the "napomena" multi-select filter option lists on the analytics pages.
    public List<String> findDistinctNotes() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT note FROM work_log_facts WHERE note IS NOT NULL ORDER BY note",
                String.class);
    }

    /*
     * Option lists come from the DIMENSION table, with the fact table asked only
     * whether the dimension was ever worked.
     *
     * WHY, in two parts:
     *
     *   Correctness. `SELECT DISTINCT operation_id, operation_name FROM
     *   work_log_facts` groups by the NAME as well as the id, and the name in the
     *   facts is a copy taken when the row was synced. Rename an operation and
     *   the same id appears twice — once per spelling — in the filter, and the
     *   report's own GROUP BY splits its totals across two rows.
     *
     *   Cost. That DISTINCT reads every fact row (≈300k a year, millions within a
     *   decade) every time a filter panel opens. The EXISTS below probes the
     *   leading column of an existing index once per dimension row and stops at
     *   the first hit: its cost is tied to the number of operations (10–15k), not
     *   to how much work has been recorded, so it does not degrade as the factory
     *   keeps working.
     *
     * `findDistinctEmployees` already worked this way — the employee name was
     * never denormalized onto the facts. These two now match it.
     */
    public List<AnalyticsOptionDto> findDistinctProducts() {
        return jdbc.getJdbcTemplate().query("""
                SELECT p.id, p.product_name
                FROM products p
                WHERE EXISTS (SELECT 1 FROM work_log_facts f WHERE f.product_id = p.id)
                ORDER BY p.product_name
                """,
                (rs, rowNum) -> new AnalyticsOptionDto(rs.getLong("id"), rs.getString("product_name")));
    }

    /**
     * Operations that were worked, optionally narrowed by a search term.
     *
     * <p>There are 10–15k operations, which is past the point where a select can
     * hold them all: the caller searches and takes a page. `search` is matched
     * case-insensitively anywhere in the name; a blank term returns the first
     * `limit` by name, which is what an untouched dropdown shows.
     */
    public List<AnalyticsOptionDto> findDistinctOperations(String search, int limit) {
        return findDistinctOperations(search, limit, null);
    }

    /**
     * The same list, narrowed to the operations of the chosen products.
     *
     * <p>Which is what a report that filters by product AND by operation needs: once a
     * product is chosen, an operation belonging to some other product is not a narrower
     * filter but an empty one — the two conditions are ANDed, so offering it would only ever
     * produce a blank report. Narrowing is on {@code operations.product_id}, the operation's
     * own product, not on what happens to have been worked together.
     */
    public List<AnalyticsOptionDto> findDistinctOperations(String search, int limit, List<Long> productIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.op_name
                FROM operations o
                WHERE EXISTS (SELECT 1 FROM work_log_facts f WHERE f.operation_id = o.id)
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (isNotEmpty(productIds)) {
            sql.append(" AND o.product_id IN (:optionProductIds)");
            params.addValue("optionProductIds", productIds);
        }

        if (search != null && !search.isBlank()) {
            sql.append(" AND o.op_name ILIKE :search");
            params.addValue("search", "%" + search.trim() + "%");
        }

        sql.append(" ORDER BY o.op_name LIMIT :limit");
        params.addValue("limit", limit);

        return jdbc.query(sql.toString(), params,
                (rs, rowNum) -> new AnalyticsOptionDto(rs.getLong("id"), rs.getString("op_name")));
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
        boolean byProduct = isProductLevel(filter);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = buildSummaryAggregate(filter, params, byProduct);
        sql.append(byProduct ? " ORDER BY product_name" : " ORDER BY product_name, operation_name");

        return jdbc.query(sql.toString(), params, SUMMARY_ROW_MAPPER);
    }

    /**
     * The same aggregate, one page at a time and sorted by the server — what page 1 asks for.
     *
     * <p>Sorting cannot be a client-side concern here: with 10–15k operations, "the ten worst"
     * is a question only the side holding all of them can answer, and a client that sorts the
     * page it happens to hold would answer a different question with the same words.
     *
     * <p>When {@code groupByProduct} is set the page is a page of PRODUCTS, not of rows: the
     * chosen products come with every operation they have, so a product band always shows that
     * product's whole total and never a part of it that looks like the whole.
     */
    public AnalyticsPageDto<ProductOperationSummaryDto> findProductOperationSummaryPage(AnalyticsFilterRequest filter) {
        boolean byProduct = isProductLevel(filter);
        // Ordering the PRODUCTS is a reordering of the bands, not a ranking that cuts across
        // them, so the bands survive it. Every other sort ranks operations against each other
        // regardless of whose product they are, which a band cannot hold.
        boolean banded = !byProduct && Boolean.TRUE.equals(filter.getGroupByProduct())
                && (filter.getSortBy() == null || "productName".equals(filter.getSortBy()));

        int size = filter.getSize() == null ? 100 : Math.max(1, Math.min(filter.getSize(), 500));
        int page = filter.getPage() == null ? 0 : Math.max(0, filter.getPage());
        String sortColumn = sortColumnOf(filter.getSortBy());
        String direction = "DESC".equalsIgnoreCase(filter.getSortDir()) ? "DESC" : "ASC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("WITH agg AS (\n");
        sql.append(buildSummaryAggregate(filter, params, byProduct));
        sql.append("\n)\n");

        if (banded) {
            // The page is chosen among the PRODUCTS the filters left standing; the join then
            // brings back all of that product's operations. Ordering inside a band follows the
            // chosen sort, so "najgora operacija" is answerable without losing the product.
            sql.append("""
                    , page_products AS (
                        SELECT product_id FROM (
                            SELECT DISTINCT product_id, product_name FROM agg
                        ) d
                        ORDER BY d.product_name""").append(" ").append(direction).append("""
                        LIMIT :limit OFFSET :offset
                    )
                    SELECT a.*, (SELECT COUNT(DISTINCT product_id) FROM agg) AS total_rows
                    FROM agg a
                    JOIN page_products pp ON pp.product_id = a.product_id
                    """);
            // The id follows each name, because a client that builds bands by walking
            // CONSECUTIVE rows would open two bands for two products sharing a name.
            sql.append(" ORDER BY a.product_name ").append(direction)
               .append(", a.product_id, a.operation_name, a.operation_id");
        } else {
            sql.append("SELECT a.*, COUNT(*) OVER () AS total_rows FROM agg a");
            sql.append(" ORDER BY a.").append(sortColumn).append(" ").append(direction).append(" NULLS LAST");
            // A tiebreaker, or two rows with the same measure could swap places between
            // requests and a page boundary would show one of them twice and the other never.
            sql.append(", a.product_name, a.operation_id");
            sql.append(" LIMIT :limit OFFSET :offset");
        }

        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        List<Long> total = new ArrayList<>(1);
        List<ProductOperationSummaryDto> rows = jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            if (total.isEmpty()) total.add(rs.getLong("total_rows"));
            return SUMMARY_ROW_MAPPER.mapRow(rs, rowNum);
        });

        return AnalyticsPageDto.of(rows, size, page, total.isEmpty() ? 0 : total.get(0));
    }

    /**
     * Sort keys the client may name, mapped to the aggregate's own columns.
     *
     * <p>A whitelist, not a passthrough: the value lands in an ORDER BY, which is one of the
     * few places a bound parameter cannot stand in for an identifier.
     */
    private String sortColumnOf(String sortBy) {
        return switch (sortBy == null ? "" : sortBy) {
            case "operationName" -> "operation_name";
            case "sumQuantity" -> "sum_quantity";
            case "sumScrap" -> "sum_scrap";
            case "avgPerHour" -> "avg_per_hour";
            case "avgPerformancePct" -> "avg_performance_pct";
            case "defectPct" -> "defect_pct";
            case "sumDurationMin" -> "sum_duration_min";
            default -> "product_name";
        };
    }

    private static final RowMapper<ProductOperationSummaryDto> SUMMARY_ROW_MAPPER = (rs, rowNum) ->
            new ProductOperationSummaryDto(
                    rs.getLong("product_id"),
                    rs.getString("product_name"),
                    (Long) rs.getObject("operation_id"),
                    rs.getString("operation_name"),
                    rs.getLong("sum_quantity"),
                    rs.getLong("sum_scrap"),
                    rs.getLong("sum_duration_min"),
                    rs.getBigDecimal("avg_per_hour"),
                    rs.getBigDecimal("defect_pct"),
                    rs.getBigDecimal("avg_performance_pct"),
                    rs.getBigDecimal("sum_weighted_performance"),
                    (Long) rs.getObject("sum_performance_duration_min")
            );

    /** The aggregate itself — SELECT … GROUP BY … HAVING …, with no ordering or paging. */
    private StringBuilder buildSummaryAggregate(
            AnalyticsFilterRequest filter, MapSqlParameterSource params, boolean byProduct) {

        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.product_id AS product_id, p.product_name AS product_name,
                """);
        // At product level the operation is not a column of the answer — it is what was
        // summed over. NULL rather than an omitted column keeps one DTO for both grains.
        sql.append(byProduct
                ? "    NULL::bigint AS operation_id, NULL::text AS operation_name,\n"
                : "    f.operation_id AS operation_id, o.op_name AS operation_name,\n");
        sql.append("""
                    SUM(quantity) AS sum_quantity,
                    SUM(scrap) AS sum_scrap,
                    SUM(duration_min) AS sum_duration_min,
                    SUM(quantity) / NULLIF(SUM(duration_min) / 60.0, 0) AS avg_per_hour,
                    SUM(scrap)::numeric / NULLIF(SUM(quantity) + SUM(scrap), 0) * 100 AS defect_pct,
                    SUM(approved_performance_rate * duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL)
                        / NULLIF(SUM(duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL), 0) AS avg_performance_pct,
                    SUM(approved_performance_rate * duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL) AS sum_weighted_performance,
                    SUM(duration_min) FILTER (WHERE approved_performance_rate IS NOT NULL) AS sum_performance_duration_min
                FROM work_log_facts f
                JOIN products p ON p.id = f.product_id
                JOIN operations o ON o.id = f.operation_id
                WHERE 1=1
                """);
        appendCommonFilters(sql, params, filter);
        // Grouped by the IDS alone. The names are functionally dependent on their
        // primary keys, so PostgreSQL allows selecting them without grouping by
        // them — and grouping by a renamed name is exactly what used to split one
        // operation's totals into two rows.
        sql.append(byProduct
                ? " GROUP BY f.product_id, p.product_name"
                : " GROUP BY f.product_id, p.product_name, f.operation_id, o.op_name");
        // The bounds are measured against the row the user is looking at, which is why they
        // are applied here and not in the frontend: at product level they must see the
        // product's total, at operation level each operation's own.
        appendAggregateBounds(sql, params, filter);

        return sql;
    }

    /**
     * Page 3 — Efikasnost radnika, one page of WORKERS at a time.
     *
     * <p>Aggregated to (worker, product, operation) grain and drawn as a tree, so the report
     * answers "how is this worker doing, and on what" rather than only "how is this worker
     * doing". Paged by WORKER for the same reason page 2 is paged by day: every band states
     * its own total, and a total is only true if the band under it is whole.
     *
     * <p>Ordering the workers keeps the tree — it reorders the bands and nothing else. Any
     * other sort is a ranking across workers, products and operations at once, which a tree
     * cannot hold, so it flattens the report and pages by row.
     *
     * <p>Reads every field of {@link AnalyticsFilterRequest} except {@code level},
     * {@code groupByProduct} and {@code groupByDate}, which belong to the other reports'
     * grains. The bounds are measured against a row of this aggregate — one worker on one
     * operation of one product — and never against a single work log.
     */
    public AnalyticsPageDto<EmployeeProductOperationDto> findEmployeeEfficiencyPage(AnalyticsFilterRequest filter) {
        boolean tree = Boolean.TRUE.equals(filter.getGroupByEmployee())
                && (filter.getSortBy() == null || "employeeName".equals(filter.getSortBy()));

        // A worker is atomic here — they arrive with every product and operation they worked
        // — so a chunk of workers is a bigger unit than a chunk of rows.
        int maxSize = tree ? 200 : 500;
        int defaultSize = tree ? 20 : 100;
        int size = filter.getSize() == null ? defaultSize : Math.max(1, Math.min(filter.getSize(), maxSize));
        int page = filter.getPage() == null ? 0 : Math.max(0, filter.getPage());
        String sortColumn = employeeTreeSortColumnOf(filter.getSortBy());
        String direction = "DESC".equalsIgnoreCase(filter.getSortDir()) ? "DESC" : "ASC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("WITH agg AS (\n");
        sql.append(buildEmployeeTreeAggregate(filter, params));
        sql.append("\n)\n");

        if (tree) {
            // The page is chosen among the WORKERS the filters left standing; the join then
            // brings back everything each of them worked.
            //
            // Every level of the ordering carries its ID after its name, because the client
            // builds the tree by walking CONSECUTIVE rows: two products sharing a name would
            // otherwise interleave and each block would open a band of its own.
            sql.append("""
                    , page_employees AS (
                        SELECT employee_id FROM (
                            SELECT DISTINCT employee_id, employee_name FROM agg
                        ) d
                        ORDER BY d.employee_name""").append(" ").append(direction).append("""
                        LIMIT :limit OFFSET :offset
                    )
                    SELECT a.*, (SELECT COUNT(DISTINCT employee_id) FROM agg) AS total_rows
                    FROM agg a
                    JOIN page_employees pe ON pe.employee_id = a.employee_id
                    ORDER BY a.employee_name""").append(" ").append(direction).append("""
                             , a.employee_id,
                               a.product_name, a.product_id,
                               a.operation_name, a.operation_id
                    """);
        } else {
            sql.append("SELECT a.*, COUNT(*) OVER () AS total_rows FROM agg a");
            sql.append(" ORDER BY a.").append(sortColumn).append(" ").append(direction).append(" NULLS LAST");
            sql.append(", a.employee_name, a.product_name, a.operation_id");
            sql.append(" LIMIT :limit OFFSET :offset");
        }

        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        List<Long> total = new ArrayList<>(1);
        List<EmployeeProductOperationDto> rows = jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            if (total.isEmpty()) total.add(rs.getLong("total_rows"));
            return EMPLOYEE_TREE_ROW_MAPPER.mapRow(rs, rowNum);
        });

        return AnalyticsPageDto.of(rows, size, page, total.isEmpty() ? 0 : total.get(0));
    }

    /**
     * Sort keys page 3 may name, mapped to the aggregate's own columns. A whitelist, not a
     * passthrough: the value lands in an ORDER BY, where a bound parameter cannot stand in
     * for an identifier.
     */
    private String employeeTreeSortColumnOf(String sortBy) {
        return switch (sortBy == null ? "" : sortBy) {
            case "productName" -> "product_name";
            case "operationName" -> "operation_name";
            case "sumQuantity" -> "sum_quantity";
            case "sumScrap" -> "sum_scrap";
            case "avgPerHour" -> "avg_per_hour";
            case "avgPerformancePct" -> "avg_performance_pct";
            case "defectPct" -> "defect_pct";
            case "sumDurationMin" -> "sum_duration_min";
            default -> "employee_name";
        };
    }

    private static final RowMapper<EmployeeProductOperationDto> EMPLOYEE_TREE_ROW_MAPPER = (rs, rowNum) ->
            new EmployeeProductOperationDto(
                    rs.getLong("employee_id"),
                    rs.getString("employee_name"),
                    rs.getLong("product_id"),
                    rs.getString("product_name"),
                    rs.getLong("operation_id"),
                    rs.getString("operation_name"),
                    rs.getLong("sum_quantity"),
                    rs.getLong("sum_scrap"),
                    rs.getLong("sum_duration_min"),
                    rs.getBigDecimal("avg_performance_pct"),
                    rs.getBigDecimal("defect_pct"),
                    rs.getBigDecimal("sum_weighted_performance"),
                    (Long) rs.getObject("sum_performance_duration_min")
            );

    /** Page 3's aggregate — SELECT … GROUP BY … HAVING …, with no ordering or paging. */
    private StringBuilder buildEmployeeTreeAggregate(AnalyticsFilterRequest filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.employee_id AS employee_id, e.full_name AS employee_name,
                    f.product_id AS product_id, p.product_name AS product_name,
                    f.operation_id AS operation_id, o.op_name AS operation_name,
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
                JOIN products p ON p.id = f.product_id
                JOIN operations o ON o.id = f.operation_id
                WHERE 1=1
                """);
        appendCommonFilters(sql, params, filter);
        sql.append(" GROUP BY f.employee_id, e.full_name, f.product_id, p.product_name,");
        sql.append(" f.operation_id, o.op_name");
        appendAggregateBounds(sql, params, filter);

        return sql;
    }

    /**
     * Page 5 — Efikasnost operacija, one page at a time.
     *
     * <p>One row per operation and nothing beneath it: this report is a LIST, not a tree. It
     * is the one report whose row count follows the number of operations (10–15k of them), so
     * paging is not a nicety — an unpaged answer is a table nobody's browser can hold.
     *
     * <p>Sorting is therefore the server's too: "the ten worst operations" is a question only
     * the side holding all of them can answer, and a client ordering the chunk it happens to
     * have would answer a different question with the same words.
     *
     * <p>The product joins for CONTEXT rather than for grain — an operation belongs to exactly
     * one, so it adds no rows, but operation names repeat across products and a row without it
     * cannot be told from another with the same name.
     *
     * <p>The bounds are measured against one operation's whole total over the filtered period,
     * never against a single work log.
     */
    public AnalyticsPageDto<OperationSummaryDto> findOperationEfficiencyPage(AnalyticsFilterRequest filter) {
        int size = filter.getSize() == null ? 100 : Math.max(1, Math.min(filter.getSize(), 500));
        int page = filter.getPage() == null ? 0 : Math.max(0, filter.getPage());
        String sortColumn = operationSortColumnOf(filter.getSortBy());
        String direction = "DESC".equalsIgnoreCase(filter.getSortDir()) ? "DESC" : "ASC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("WITH agg AS (\n");
        sql.append(buildOperationAggregate(filter, params));
        sql.append("\n)\n");
        sql.append("SELECT a.*, COUNT(*) OVER () AS total_rows FROM agg a");
        sql.append(" ORDER BY a.").append(sortColumn).append(" ").append(direction).append(" NULLS LAST");
        // A tiebreaker, or two operations with the same measure could swap places between
        // requests and a page boundary would show one of them twice and the other never.
        sql.append(", a.operation_name, a.operation_id");
        sql.append(" LIMIT :limit OFFSET :offset");

        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        List<Long> total = new ArrayList<>(1);
        List<OperationSummaryDto> rows = jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            if (total.isEmpty()) total.add(rs.getLong("total_rows"));
            return new OperationSummaryDto(
                    rs.getLong("operation_id"),
                    rs.getString("operation_name"),
                    rs.getLong("product_id"),
                    rs.getString("product_name"),
                    rs.getLong("sum_quantity"),
                    rs.getLong("sum_scrap"),
                    rs.getLong("sum_duration_min"),
                    rs.getBigDecimal("avg_performance_pct"),
                    rs.getBigDecimal("defect_pct")
            );
        });

        return AnalyticsPageDto.of(rows, size, page, total.isEmpty() ? 0 : total.get(0));
    }

    /**
     * Sort keys page 5 may name, mapped to the aggregate's own columns. A whitelist, not a
     * passthrough: the value lands in an ORDER BY, where a bound parameter cannot stand in
     * for an identifier.
     *
     * <p>There is no worker here to sort by. The report is about operations; who ran them is
     * the worker report's question.
     */
    private String operationSortColumnOf(String sortBy) {
        return switch (sortBy == null ? "" : sortBy) {
            case "productName" -> "product_name";
            case "sumQuantity" -> "sum_quantity";
            case "sumScrap" -> "sum_scrap";
            case "avgPerHour" -> "avg_per_hour";
            case "avgPerformancePct" -> "avg_performance_pct";
            case "defectPct" -> "defect_pct";
            case "sumDurationMin" -> "sum_duration_min";
            default -> "operation_name";
        };
    }

    /** Page 5's aggregate — SELECT … GROUP BY … HAVING …, with no ordering or paging. */
    private StringBuilder buildOperationAggregate(AnalyticsFilterRequest filter, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.operation_id AS operation_id, o.op_name AS operation_name,
                    f.product_id AS product_id, p.product_name AS product_name,
                    SUM(f.quantity) AS sum_quantity,
                    SUM(f.scrap) AS sum_scrap,
                    SUM(f.duration_min) AS sum_duration_min,
                    SUM(f.quantity) / NULLIF(SUM(f.duration_min) / 60.0, 0) AS avg_per_hour,
                    SUM(f.scrap)::numeric / NULLIF(SUM(f.quantity) + SUM(f.scrap), 0) * 100 AS defect_pct,
                    SUM(f.approved_performance_rate * f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL)
                        / NULLIF(SUM(f.duration_min) FILTER (WHERE f.approved_performance_rate IS NOT NULL), 0) AS avg_performance_pct
                FROM work_log_facts f
                JOIN operations o ON o.id = f.operation_id
                JOIN products p ON p.id = f.product_id
                WHERE 1=1
                """);
        appendCommonFilters(sql, params, filter);
        // Grouped by the IDS, with the names read from the dimension tables — grouping by a
        // name copied onto the facts is what used to split a renamed operation into two rows.
        sql.append(" GROUP BY f.operation_id, o.op_name, f.product_id, p.product_name");
        appendAggregateBounds(sql, params, filter);

        return sql;
    }

    /**
     * Page 2 — Datum-smena-proizvod-operacija-radnik, one page of DATES at a time.
     *
     * <p>Always pre-aggregated to (date, shift, product, operation, employee) grain, so what
     * travels is bounded by distinct combinations rather than by how many work logs the
     * factory recorded. This is also the only query that joins {@code employees} for display
     * — the worker's name is not denormalized onto the facts, because page 2 is the only page
     * that names one.
     *
     * <p>Paged, sorted and bounded on the SERVER, for page 1's reason: over a year the report
     * is far more rows than a browser can hold, so "the ten worst operations" is a question
     * only the side holding all of them can answer, and a client sorting the chunk it happens
     * to have would answer a different question with the same words.
     *
     * <p>Two ways to page, and they page different things:
     * <ul>
     *   <li>{@code groupByDate} (the default view, nothing sorted) — a page is a page of
     *       DATES. A date arrives with every shift, product, operation and worker on it, so a
     *       day's or a shift's subtotal on screen is always the whole of it.
     *   <li>anything sorted — the tree is not a ranking, so a chosen sort flattens it and a
     *       page is a page of rows, ordered across everything the filters left standing.
     * </ul>
     *
     * <p>Reads every field of {@link AnalyticsFilterRequest} except {@code level} and
     * {@code groupByProduct}, which belong to page 1's product/operation grain.
     */
    public AnalyticsPageDto<ProductDateOperationEmployeeDto> findDateTreePage(AnalyticsFilterRequest filter) {
        // Ordering the DAYS is a reordering of the bands, not a ranking that cuts across them,
        // so the tree survives it — "najnoviji dan prvo" is still a tree. Every other sort
        // ranks things that live at different depths and flattens it.
        boolean tree = Boolean.TRUE.equals(filter.getGroupByDate())
                && (filter.getSortBy() == null || "workDate".equals(filter.getSortBy()));

        // A day is atomic here, so a chunk of dates is a much bigger unit than a chunk of
        // rows — hence the smaller default and the lower ceiling in tree mode.
        int maxSize = tree ? 60 : 500;
        int defaultSize = tree ? 7 : 100;
        int size = filter.getSize() == null ? defaultSize : Math.max(1, Math.min(filter.getSize(), maxSize));
        int page = filter.getPage() == null ? 0 : Math.max(0, filter.getPage());
        String sortColumn = dateTreeSortColumnOf(filter.getSortBy());
        String direction = "DESC".equalsIgnoreCase(filter.getSortDir()) ? "DESC" : "ASC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("WITH agg AS (\n");
        sql.append(buildDateTreeAggregate(filter, params));
        sql.append("\n)\n");

        if (tree) {
            // The page is chosen among the DATES the filters left standing; the join then
            // brings back everything recorded on those days.
            //
            // Every level of the ordering carries its ID after its name, because the client
            // builds the tree by walking CONSECUTIVE rows: two products that happen to share
            // a name would otherwise be free to interleave, and each block of them would open
            // a band of its own. The id makes each one a run.
            sql.append("""
                    , page_dates AS (
                        SELECT work_date FROM (
                            SELECT DISTINCT work_date FROM agg
                        ) d
                        ORDER BY d.work_date""").append(" ").append(direction).append("""
                        LIMIT :limit OFFSET :offset
                    )
                    SELECT a.*, (SELECT COUNT(DISTINCT work_date) FROM agg) AS total_rows
                    FROM agg a
                    JOIN page_dates pd ON pd.work_date = a.work_date
                    ORDER BY a.work_date""").append(" ").append(direction).append("""
                             , a.shift_start_time, a.shift_code, a.shift_type_id,
                               a.product_name, a.product_id,
                               a.operation_name, a.operation_id,
                               a.employee_name, a.employee_id
                    """);
        } else {
            sql.append("SELECT a.*, COUNT(*) OVER () AS total_rows FROM agg a");
            sql.append(" ORDER BY a.").append(sortColumn).append(" ").append(direction).append(" NULLS LAST");
            // A tiebreaker, or two rows with the same measure could swap places between
            // requests and a page boundary would show one of them twice and the other never.
            sql.append(", a.work_date, a.shift_start_time, a.product_name, a.operation_id, a.employee_id");
            sql.append(" LIMIT :limit OFFSET :offset");
        }

        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);

        List<Long> total = new ArrayList<>(1);
        List<ProductDateOperationEmployeeDto> rows = jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            if (total.isEmpty()) total.add(rs.getLong("total_rows"));
            return DATE_TREE_ROW_MAPPER.mapRow(rs, rowNum);
        });

        return AnalyticsPageDto.of(rows, size, page, total.isEmpty() ? 0 : total.get(0));
    }

    /**
     * Sort keys page 2 may name, mapped to the aggregate's own columns.
     *
     * <p>A whitelist, not a passthrough: the value lands in an ORDER BY, one of the few places
     * a bound parameter cannot stand in for an identifier.
     *
     * <p>"smena" sorts by when the shift STARTS, not by how its code is spelled — I, II, III
     * is a chronological order that happens to also be alphabetical, and stops being so the
     * moment a factory names its shifts anything else.
     */
    private String dateTreeSortColumnOf(String sortBy) {
        return switch (sortBy == null ? "" : sortBy) {
            case "shiftCode" -> "shift_start_time";
            case "productName" -> "product_name";
            case "operationName" -> "operation_name";
            case "employeeName" -> "employee_name";
            case "sumQuantity" -> "sum_quantity";
            case "sumScrap" -> "sum_scrap";
            case "avgPerHour" -> "avg_per_hour";
            case "avgPerformancePct" -> "avg_performance_pct";
            case "defectPct" -> "defect_pct";
            case "sumDurationMin" -> "sum_duration_min";
            default -> "work_date";
        };
    }

    private static final RowMapper<ProductDateOperationEmployeeDto> DATE_TREE_ROW_MAPPER = (rs, rowNum) ->
            new ProductDateOperationEmployeeDto(
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
            );

    /**
     * Each operation's norm, beside what the filtered work says its norm could be.
     *
     * <p>Built on page 2's own aggregate, which is the point: the candidate has to be derived
     * from exactly the work the report is showing, bounds and all. A reader who narrows the
     * period, drops a shift or sets "učinak od 90%" is deciding what counts as representative
     * work, and the norm they are offered has to follow that decision rather than quietly
     * answer about everything ever recorded.
     *
     * <p>Summed from the aggregate rather than from the facts: the HAVING bounds are measured
     * against a row of that aggregate — one worker, one operation, one shift, one day — so
     * they have to be applied before anything is totalled across it.
     *
     * <p>A norm is pieces per HOUR, so it is SUM(quantity) / SUM(hours) and never the average
     * of each row's own rate: a worker who ran ten minutes must not weigh as much as one who
     * ran all shift.
     */
    public List<NormBasisDto> findNormBasis(AnalyticsFilterRequest filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("WITH agg AS (\n");
        sql.append(buildDateTreeAggregate(filter, params));
        sql.append("\n)\n");
        sql.append("""
                SELECT
                    a.operation_id AS operation_id,
                    a.operation_name AS operation_name,
                    o.min_norm AS current_norm,
                    o.norm_date AS norm_date,
                    o.units_per_product AS units_per_product,
                    SUM(a.sum_quantity) AS sum_quantity,
                    SUM(a.sum_duration_min) AS sum_duration_min,
                    SUM(a.sum_quantity) / NULLIF(SUM(a.sum_duration_min) / 60.0, 0) AS avg_per_hour
                FROM agg a
                JOIN operations o ON o.id = a.operation_id
                GROUP BY a.operation_id, a.operation_name, o.min_norm, o.norm_date, o.units_per_product
                ORDER BY a.operation_name
                """);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new NormBasisDto(
                rs.getLong("operation_id"),
                rs.getString("operation_name"),
                (Integer) rs.getObject("current_norm"),
                rs.getObject("norm_date", java.time.LocalDate.class),
                (Integer) rs.getObject("units_per_product"),
                rs.getLong("sum_quantity"),
                rs.getLong("sum_duration_min"),
                rs.getBigDecimal("avg_per_hour")
        ));
    }

    /** Page 2's aggregate — SELECT … GROUP BY … HAVING …, with no ordering or paging. */
    private StringBuilder buildDateTreeAggregate(AnalyticsFilterRequest filter, MapSqlParameterSource params) {
        // The shift's code and start time are read from the SHIFTS table, not from the copy
        // denormalized onto the facts: the copy is taken when a row is synced, so renaming a
        // shift would put the same shift_type_id in the GROUP BY under two spellings and split
        // one shift's totals across two bands. Same reasoning as findDistinctProducts.
        StringBuilder sql = new StringBuilder("""
                SELECT
                    f.work_date AS work_date,
                    f.shift_type_id AS shift_type_id, s.shift_code AS shift_code,
                    s.start_time AS shift_start_time,
                    f.product_id AS product_id, p.product_name AS product_name,
                    f.operation_id AS operation_id, o.op_name AS operation_name,
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
                JOIN products p ON p.id = f.product_id
                JOIN operations o ON o.id = f.operation_id
                JOIN shifts s ON s.id = f.shift_type_id
                WHERE 1=1
                """);
        appendCommonFilters(sql, params, filter);
        sql.append(" GROUP BY f.work_date, f.shift_type_id, s.shift_code, s.start_time,");
        sql.append(" f.product_id, p.product_name, f.operation_id, o.op_name,");
        sql.append(" f.employee_id, e.full_name");
        // Measured against the row the reader is looking at — one worker's work on one
        // operation, on one shift of one day — and never against a single work log.
        appendAggregateBounds(sql, params, filter);

        return sql;
    }
}
