-- =============================================================================
-- The scrap nobody reported is a month and a product
-- =============================================================================
-- WHAT CHANGES
--   scraps -> monthly_scraps, with its indexes, keys and checks renamed to match.
--   monthly_scraps.product_id — new, NOT NULL, and tied to the operation's own
--     product by a composite foreign key so the two cannot disagree.
--   audit_tables — the registered name follows the table.
--
-- WHY THE NAME
--   The table holds what was scrapped and never reported during a month, counted
--   once at the end of it. `period` is already the first day of a month and the
--   database already insists on it (chk_scraps_period_month). "scraps" reads as
--   every scrap there is — including the per-operation `scrap` column on
--   work_logs, which is a different thing recorded daily by a different person.
--   One of the two had to say which it was.
--
-- WHY period STAYS, AND NOT start_date + end_date
--   They would be two columns for one fact. Both would have to be the first and
--   last day of the SAME month, which needs a check asserting exactly that — and
--   once it exists the second column carries nothing the first did not, while
--   adding a second way for a row to be inconsistent. The month's end is one
--   expression away (period + INTERVAL '1 month' - 1 day), grouping by month is
--   simpler with one column, and the indexes are already on `period`.
--
-- WHY product_id IS TIED, NOT MERELY ADDED
--   operations.product_id is NOT NULL, so the operation already determines the
--   product: a plain product_id beside it would be the same fact stored twice,
--   free to drift the moment somebody re-points the operation. This is a table
--   read weeks later, when nobody remembers what was entered — the worst place
--   for a column that may quietly disagree with its neighbour.
--
--   A CHECK cannot look at another table, so the guarantee is a COMPOSITE
--   FOREIGN KEY: (operation_id, product_id) must exist together on `operations`.
--   That needs a unique key on operations (id, product_id) — redundant in
--   content, since id alone is unique, and required by the FK. Same pattern as
--   manufacturing_time_requests uses for its order line items (V17).
--
-- WHAT HAPPENS TO EXISTING DATA
--   The rename keeps every row, index and trigger; Postgres carries them across.
--   product_id is backfilled from each row's own operation before the NOT NULL
--   lands, so no row has to be invented or deleted. The table is unused by the
--   application today — no entity, no repository, no endpoint — so nothing in
--   the backend had to follow this rename.
--
-- WHY audit_tables MUST BE UPDATED IN THE SAME BREATH
--   audit_trigger_fn resolves the table by NAME:
--       SELECT ... FROM audit_tables WHERE table_name = TG_TABLE_NAME
--   Renaming the table and leaving the registration behind makes that lookup
--   find nothing, table_id comes out NULL, and the NOT NULL on it turns every
--   INSERT, UPDATE and DELETE on the table into an error. The rename and the
--   registration are one change, not two.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. The name
-- -----------------------------------------------------------------------------

ALTER TABLE public.scraps RENAME TO monthly_scraps;

-- Postgres keeps indexes, constraints and triggers across a rename under their
-- OLD names. Left alone they would spell a table that no longer exists, which is
-- what the next person reads when one of them fails.
ALTER INDEX idx_scraps_operation_id RENAME TO idx_monthly_scraps_operation_id;
ALTER INDEX idx_scraps_period RENAME TO idx_monthly_scraps_period;
ALTER INDEX idx_scraps_period_operation RENAME TO idx_monthly_scraps_period_operation;
ALTER INDEX idx_scraps_production_order_id RENAME TO idx_monthly_scraps_production_order_id;
ALTER INDEX scraps_pkey RENAME TO monthly_scraps_pkey;

ALTER TABLE public.monthly_scraps
    RENAME CONSTRAINT fk_scraps_operation_id TO fk_monthly_scraps_operation_id;
ALTER TABLE public.monthly_scraps
    RENAME CONSTRAINT fk_scraps_production_order_id TO fk_monthly_scraps_production_order_id;
ALTER TABLE public.monthly_scraps
    RENAME CONSTRAINT chk_scraps_period_month TO chk_monthly_scraps_period_month;
ALTER TABLE public.monthly_scraps
    RENAME CONSTRAINT chk_scraps_quantity TO chk_monthly_scraps_quantity;

ALTER TRIGGER trg_02_scraps_archived_at ON public.monthly_scraps
    RENAME TO trg_02_monthly_scraps_archived_at;
ALTER TRIGGER trg_03_scraps_updated_at ON public.monthly_scraps
    RENAME TO trg_03_monthly_scraps_updated_at;
ALTER TRIGGER trg_audit_logs_scraps ON public.monthly_scraps
    RENAME TO trg_audit_logs_monthly_scraps;

-- The audit trigger finds its table by name. Without this every write to the
-- table fails on a NULL table_id.
UPDATE public.audit_tables SET table_name = 'monthly_scraps' WHERE table_name = 'scraps';


-- -----------------------------------------------------------------------------
-- 2. Which product was scrapped
-- -----------------------------------------------------------------------------

ALTER TABLE public.monthly_scraps ADD COLUMN product_id bigint;

-- Every row already knows, through its operation. Backfilled before NOT NULL so
-- existing rows pass rather than having to be invented or removed.
UPDATE public.monthly_scraps ms
SET product_id = o.product_id
FROM public.operations o
WHERE o.id = ms.operation_id;

ALTER TABLE public.monthly_scraps ALTER COLUMN product_id SET NOT NULL;

-- The target of the composite key below. Redundant in content — id is already
-- unique — and required, because a foreign key can only reference columns
-- covered by a unique constraint.
ALTER TABLE public.operations
    ADD CONSTRAINT uq_operations_id_product UNIQUE (id, product_id);

-- The guarantee: the product on a scrap row is the product of the operation on
-- that same row, checked by the database on every insert and every update.
ALTER TABLE public.monthly_scraps
    ADD CONSTRAINT fk_monthly_scraps_operation_product
        FOREIGN KEY (operation_id, product_id)
        REFERENCES public.operations (id, product_id)
        ON DELETE RESTRICT;

CREATE INDEX idx_monthly_scraps_product_id ON public.monthly_scraps (product_id);
