package com.aleksandarparipovic.marel_app.dashboard;

import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.CompletedRequestRow;
import com.aleksandarparipovic.marel_app.dashboard.dto.CommercialDashboardResponse.DeliveredOrderRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The live reads behind the commercial board.
 *
 * <p>Its own class beside the admin and supervisor repositories for the same
 * reason those are two: these queries answer the commercial audience's
 * questions. Everything here is bounded by {@code :limit} or is a COUNT.
 *
 * <p>What is NOT here is anything about roks or progress — the effective
 * deadline and the razrada arithmetic live in {@code ProductionOrderService}
 * and {@code OrderProgressService}, and the board's service composes those
 * rather than letting a second SQL spelling of "late" drift from the first.
 */
@Repository
@RequiredArgsConstructor
public class CommercialDashboardQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    // ── Delivered lately ────────────────────────────────────────────────────

    /**
     * Production orders delivered within the window, newest first.
     *
     * <p>{@code updated_at} stands in for the delivery moment — the table keeps
     * no delivery timestamp, and flipping the status is the last write an order
     * normally sees. The row DTO's javadoc carries the caveat.
     */
    public List<DeliveredOrderRow> findDeliveredProductionOrders(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT po.id, po.code, po.name, c.name AS customer_name, po.updated_at
                FROM production_orders po
                LEFT JOIN customers c ON c.id = po.customer_id
                WHERE po.status = 'DELIVERED'
                  AND po.is_active = true AND po.archived_at IS NULL
                  AND po.updated_at >= :since
                ORDER BY po.updated_at DESC, po.id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new DeliveredOrderRow(
                        CommercialDashboardResponse.KIND_PRODUCTION,
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("customer_name"),
                        offsetDateTime(rs, "updated_at")));
    }

    public long countDeliveredProductionOrders(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM production_orders
                WHERE status = 'DELIVERED' AND is_active = true AND archived_at IS NULL
                  AND updated_at >= :since
                """, new MapSqlParameterSource("since", since));
    }

    /** Sample orders closed within the window, newest first. Same timestamp caveat. */
    public List<DeliveredOrderRow> findClosedSampleOrders(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT so.id, so.code, so.name, c.name AS customer_name, so.updated_at
                FROM sample_orders so
                LEFT JOIN customers c ON c.id = so.customer_id
                WHERE LOWER(so.status) = 'closed'
                  AND so.is_active = true AND so.archived_at IS NULL
                  AND so.updated_at >= :since
                ORDER BY so.updated_at DESC, so.id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new DeliveredOrderRow(
                        CommercialDashboardResponse.KIND_SAMPLE,
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("customer_name"),
                        offsetDateTime(rs, "updated_at")));
    }

    public long countClosedSampleOrders(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM sample_orders
                WHERE LOWER(status) = 'closed' AND is_active = true AND archived_at IS NULL
                  AND updated_at >= :since
                """, new MapSqlParameterSource("since", since));
    }

    // ── The open book, for the progress ranking ─────────────────────────────

    /** A slim open production order — what the progress ranking hangs its percent on. */
    public record OpenOrderRef(Long id, String code, String name, String customerName) {}

    /**
     * Every open production order, one slim row each.
     *
     * <p>The whole open set, not a page: the ranking needs every candidate
     * before it can say which are the fullest and the emptiest. The limit only
     * guards against the absurd — an open book past it has bigger problems than
     * this board.
     */
    public List<OpenOrderRef> findOpenProductionOrders(int limit) {
        return jdbc.query("""
                SELECT po.id, po.code, po.name, c.name AS customer_name
                FROM production_orders po
                LEFT JOIN customers c ON c.id = po.customer_id
                WHERE po.status = 'CREATED' AND po.is_active = true AND po.archived_at IS NULL
                ORDER BY po.id DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> new OpenOrderRef(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("customer_name")));
    }

    // ── Requests answered lately ────────────────────────────────────────────

    /**
     * Vreme-izrade requests completed within the window, newest answer first.
     *
     * <p>{@code internal = false} exactly as the queues filter: a supervisor's
     * self-request was never anybody's ask, and its answer is nobody's news.
     * The occasion order (production or sample) is joined through the line the
     * request was raised on, so the row can say what the answer was FOR.
     */
    public List<CompletedRequestRow> findCompletedTimeRequests(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT r.id,
                       p.id                AS product_id,
                       p.product_name      AS product_name,
                       po.id               AS production_order_id,
                       po.code             AS production_order_code,
                       po.name             AS production_order_name,
                       so.id               AS sample_order_id,
                       so.code             AS sample_order_code,
                       requester.full_name AS created_by_name,
                       processor.full_name AS processed_by_name,
                       r.processed_at,
                       r.result_manufacturing_time_id
                FROM manufacturing_time_requests r
                JOIN products p ON p.id = r.product_id
                LEFT JOIN users requester ON requester.id = r.created_by
                LEFT JOIN users processor ON processor.id = r.processed_by
                LEFT JOIN production_order_line_items poli ON poli.id = r.production_order_line_item_id
                LEFT JOIN production_orders po ON po.id = poli.production_order_id
                LEFT JOIN sample_order_line_items soli ON soli.id = r.sample_order_line_item_id
                LEFT JOIN sample_orders so ON so.id = soli.sample_order_id
                WHERE r.status = 'COMPLETED'
                  AND r.internal = false
                  AND r.processed_at >= :since
                ORDER BY r.processed_at DESC, r.id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new CompletedRequestRow(
                        CommercialDashboardResponse.REQUEST_MANUFACTURING_TIME,
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getString("product_name"),
                        nullableLong(rs, "production_order_id"),
                        rs.getString("production_order_code"),
                        rs.getString("production_order_name"),
                        nullableLong(rs, "sample_order_id"),
                        rs.getString("sample_order_code"),
                        rs.getString("created_by_name"),
                        rs.getString("processed_by_name"),
                        offsetDateTime(rs, "processed_at"),
                        nullableLong(rs, "result_manufacturing_time_id")));
    }

    public long countCompletedTimeRequests(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM manufacturing_time_requests
                WHERE status = 'COMPLETED' AND internal = false AND processed_at >= :since
                """, new MapSqlParameterSource("since", since));
    }

    /**
     * Razrada requests completed within the window, newest answer first.
     *
     * <p>{@code result_state = 'SUBMITTED'} restates what COMPLETED already
     * implies, spelled out because everything downstream (the progress
     * denominator, the read-only answer) hangs on the submitted state, not on
     * the status.
     */
    public List<CompletedRequestRow> findCompletedScopeRequests(OffsetDateTime since, int limit) {
        return jdbc.query("""
                SELECT r.id,
                       po.id               AS production_order_id,
                       po.code             AS production_order_code,
                       po.name             AS production_order_name,
                       requester.full_name AS created_by_name,
                       processor.full_name AS processed_by_name,
                       r.processed_at
                FROM production_order_scope_requests r
                JOIN production_orders po ON po.id = r.production_order_id
                LEFT JOIN users requester ON requester.id = r.created_by
                LEFT JOIN users processor ON processor.id = r.processed_by
                WHERE r.status = 'COMPLETED'
                  AND r.result_state = 'SUBMITTED'
                  AND r.internal = false
                  AND r.processed_at >= :since
                ORDER BY r.processed_at DESC, r.id DESC
                LIMIT :limit
                """,
                params(since, limit),
                (rs, i) -> new CompletedRequestRow(
                        CommercialDashboardResponse.REQUEST_SCOPE,
                        rs.getLong("id"),
                        null,
                        null,
                        rs.getLong("production_order_id"),
                        rs.getString("production_order_code"),
                        rs.getString("production_order_name"),
                        null,
                        null,
                        rs.getString("created_by_name"),
                        rs.getString("processed_by_name"),
                        offsetDateTime(rs, "processed_at"),
                        null));
    }

    public long countCompletedScopeRequests(OffsetDateTime since) {
        return count("""
                SELECT COUNT(*) FROM production_order_scope_requests
                WHERE status = 'COMPLETED' AND result_state = 'SUBMITTED'
                  AND internal = false AND processed_at >= :since
                """, new MapSqlParameterSource("since", since));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static MapSqlParameterSource params(OffsetDateTime since, int limit) {
        return new MapSqlParameterSource("since", since).addValue("limit", limit);
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0L : value;
    }

    private static OffsetDateTime offsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
