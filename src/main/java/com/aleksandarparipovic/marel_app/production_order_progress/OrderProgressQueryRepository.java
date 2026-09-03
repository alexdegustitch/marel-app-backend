package com.aleksandarparipovic.marel_app.production_order_progress;

import com.aleksandarparipovic.marel_app.production_order_line_item.ProductionOrderLineItem;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationOutputRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationRefRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.OperationRequirementRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.ScopeRequirementRow;
import com.aleksandarparipovic.marel_app.production_order_progress.dto.UnscopedLineCountRow;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * The two halves of an order's progress, read straight from the tables that hold
 * them: what the agreed scope asks for, and what the floor actually recorded.
 *
 * <p>Its own repository rather than methods spread over three existing ones,
 * because every query here spans the scope request, the order lines and the
 * work logs and belongs to none of them.
 *
 * <p><b>Everything is bounded by the orders asked about,</b> which is the whole
 * performance story. The work-log sum reads only the slice of
 * {@code idx_work_logs_order_operation_live} belonging to those orders and never
 * opens the table, so it costs the same on thirty million logs as on a hundred
 * thousand. The measured figures are in migration V33.
 *
 * <p>Callers must not pass an empty collection: an empty {@code IN} is not a
 * question PostgreSQL accepts.
 */
@org.springframework.stereotype.Repository
public interface OrderProgressQueryRepository
        extends org.springframework.data.repository.Repository<ProductionOrderLineItem, Long> {

    /**
     * What the agreed scope asks for: one row per needed operation per line.
     *
     * <p>SUBMITTED only. A draft is somebody still working it out, and reading it
     * would give an order a denominator nobody has agreed to. An operation the
     * processor marked "not needed" is a decision rather than a requirement, so
     * it is left out here and cannot hold a product back.
     */
    @Query(value = """
        SELECT li.production_order_id      AS orderId,
               li.id                       AS lineItemId,
               li.product_id               AS productId,
               p.product_name              AS productName,
               op.operation_id             AS operationId,
               op.operation_name           AS operationName,
               op.units_per_product_value  AS unitsPerProduct,
               li.quantity                 AS lineQuantity
        FROM production_order_scope_requests r
        JOIN production_order_scope_request_items i
             ON i.request_id = r.id
        JOIN production_order_scope_request_operations op
             ON op.request_item_id = i.id
        JOIN production_order_line_items li
             ON li.id = i.production_order_line_item_id
        JOIN products p ON p.id = li.product_id
        WHERE li.production_order_id IN (:orderIds)
          AND r.status = 'COMPLETED'
          AND r.result_state = 'SUBMITTED'
          AND op.needed
          AND op.units_per_product_value IS NOT NULL
          AND li.is_active
        ORDER BY li.production_order_id, li.line_order, op.line_order, op.id
        """, nativeQuery = true)
    List<ScopeRequirementRow> findRequirements(@Param("orderIds") Collection<Long> orderIds);

    /**
     * What was recorded: pieces and scrap per order and operation.
     *
     * <p>The predicate is spelled exactly as
     * {@code idx_work_logs_order_operation_live} is defined, so the index applies
     * and the aggregate is index-only. Withdrawn logs are not in the index at
     * all, so a soft delete removes the pieces from the sum by removing the row
     * from the index rather than by being filtered out afterwards.
     */
    @Query(value = """
        SELECT wl.production_order_id           AS orderId,
               wl.operation_id                  AS operationId,
               COALESCE(SUM(wl.quantity), 0)    AS donePieces,
               COALESCE(SUM(wl.scrap), 0)       AS scrapPieces
        FROM work_logs wl
        WHERE wl.production_order_id IN (:orderIds)
          AND wl.is_active
          AND wl.archived_at IS NULL
          AND wl.production_order_id IS NOT NULL
        GROUP BY wl.production_order_id, wl.operation_id
        """, nativeQuery = true)
    List<OperationOutputRow> findOutput(@Param("orderIds") Collection<Long> orderIds);

    /**
     * How many active lines of each order nobody has agreed a scope for.
     *
     * <p>Counted so a screen can say "three of five lines have no razrada"
     * instead of showing a percentage that quietly leaves them out.
     */
    @Query(value = """
        SELECT li.production_order_id AS orderId,
               COUNT(*)               AS lineCount
        FROM production_order_line_items li
        WHERE li.production_order_id IN (:orderIds)
          AND li.is_active
          AND NOT EXISTS (
              SELECT 1
              FROM production_order_scope_request_items i
              JOIN production_order_scope_requests r ON r.id = i.request_id
              WHERE i.production_order_line_item_id = li.id
                AND r.status = 'COMPLETED'
                AND r.result_state = 'SUBMITTED')
        GROUP BY li.production_order_id
        """, nativeQuery = true)
    List<UnscopedLineCountRow> countLinesWithoutScope(@Param("orderIds") Collection<Long> orderIds);

    /** Names for operations that were worked but are not in the order's scope. */
    @Query(value = """
        SELECT o.id           AS operationId,
               o.op_name      AS operationName,
               p.id           AS productId,
               p.product_name AS productName
        FROM operations o
        JOIN products p ON p.id = o.product_id
        WHERE o.id IN (:operationIds)
        """, nativeQuery = true)
    List<OperationRefRow> findOperationRefs(@Param("operationIds") Collection<Long> operationIds);

    /**
     * What the agreed scopes ask of ONE operation, per order.
     *
     * <p>The mirror of {@link #findRequirements}, for the operation detail page:
     * that screen holds an operation and wants every order's requirement for it,
     * rather than one order's requirement for everything.
     */
    @Query(value = """
        SELECT li.production_order_id AS orderId,
               SUM(li.quantity * op.units_per_product_value) AS requiredPieces
        FROM production_order_scope_requests r
        JOIN production_order_scope_request_items i
             ON i.request_id = r.id
        JOIN production_order_scope_request_operations op
             ON op.request_item_id = i.id
        JOIN production_order_line_items li
             ON li.id = i.production_order_line_item_id
        WHERE op.operation_id = :operationId
          AND r.status = 'COMPLETED'
          AND r.result_state = 'SUBMITTED'
          AND op.needed
          AND op.units_per_product_value IS NOT NULL
          AND li.is_active
        GROUP BY li.production_order_id
        """, nativeQuery = true)
    List<OperationRequirementRow> findRequiredPiecesForOperation(@Param("operationId") Long operationId);
}
