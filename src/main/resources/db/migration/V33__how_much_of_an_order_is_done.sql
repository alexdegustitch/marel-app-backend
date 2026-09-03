-- =============================================================================
-- How much of an order is done
-- =============================================================================
-- WHAT CHANGES
--   Two indexes on work_logs. No table, column, constraint, trigger, function
--   or row is altered, and nothing here changes what any query returns — only
--   how long it takes to return it.
--
-- WHY
--   "How much of this order is done" is answered by summing the pieces recorded
--   against the order, per operation, and comparing them with what the order's
--   agreed scope asks for (production_order_scope_request_operations). The sum
--   is computed when the screen is read rather than kept as a counter, so it
--   cannot drift away from the work logs it describes.
--
--   That only holds up if the sum reads the order's OWN logs and nothing else.
--   These two indexes are what makes it so. Measured on a stand-in table of
--   30 000 000 work logs (5.4 GB heap) on PostgreSQL 18:
--
--     one ordinary order (1 407 logs)          1.6 ms   35 pages read
--     one page of 25 orders (35 152 logs)      3.6 ms
--     one operation across its 40 orders       4.4 ms
--     one order of a million logs             68   ms
--     the same ordinary order, no such index 1605   ms   whole table scanned
--
--   A thousandfold difference on the same question, which is the whole reason
--   this file exists. INCLUDE carries the two summed columns into the index, so
--   the aggregate never opens the table at all (Heap Fetches: 0 above).
--
--   The second index serves the mirror question — "this operation, across the
--   orders it is worked for" — which the operation detail page ALREADY asks
--   (WorkLogRepository.sumOutputPerOrderForOperation). Until now it had only
--   idx_work_logs_operation_id and went to the heap for every row it summed.
--
--   Both are partial on exactly the predicate those queries use, so archived and
--   withdrawn logs are not in the index at all: it stays small, and a soft
--   delete removes the row from it rather than leaving it to be filtered out.
--
-- MIGRATION IMPACT
--   · Additive only. Nothing that runs today can break.
--   · Disk: about 1.1 GB each per 30 000 000 live rows; proportionally less
--     for a smaller table. Today's work_logs is far below that.
--   · Plain CREATE INDEX, which Flyway runs inside its transaction. On a table
--     that already holds millions of rows this blocks writes to it while the
--     index builds (21 s for 30 000 000 rows on the measurement machine). If
--     that matters for a deployment, build them ahead of time with CREATE INDEX
--     CONCURRENTLY under the same names — IF NOT EXISTS makes this script a
--     no-op for the ones that already exist.
--   · Rollback:
--       DROP INDEX public.idx_work_logs_order_operation_live;
--       DROP INDEX public.idx_work_logs_operation_order_live;
--     Nothing depends on either; the queries simply get slower again.
-- =============================================================================

-- "What has been done on THIS order, per operation."
CREATE INDEX IF NOT EXISTS idx_work_logs_order_operation_live
    ON public.work_logs (production_order_id, operation_id)
    INCLUDE (quantity, scrap)
    WHERE is_active AND archived_at IS NULL AND production_order_id IS NOT NULL;

-- "What has been done with THIS operation, per order."
CREATE INDEX IF NOT EXISTS idx_work_logs_operation_order_live
    ON public.work_logs (operation_id, production_order_id)
    INCLUDE (quantity, scrap)
    WHERE is_active AND archived_at IS NULL AND production_order_id IS NOT NULL;

COMMENT ON INDEX public.idx_work_logs_order_operation_live IS
    'Order progress: the pieces recorded against one order, grouped by operation. Index-only, so the aggregate never reads the table.';
COMMENT ON INDEX public.idx_work_logs_operation_order_live IS
    'Operation usage: the pieces recorded with one operation, grouped by order. Index-only.';
