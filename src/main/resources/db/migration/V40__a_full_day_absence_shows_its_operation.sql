-- =============================================================================
-- A full-day absence shows its operation
-- =============================================================================
-- WHAT CHANGES
--   1. work_code_categories gains one column:
--        is_full_day  boolean NOT NULL DEFAULT false
--      TRUE for a category that always means a WHOLE shift nobody worked —
--      godišnji odmor, every kind of bolovanje, neplaćeno odsustvo, neradni
--      dan. It is the single flag three things now read instead of matching the
--      codes NO/ND by hand:
--        · the daily recalculation, which drops a full-day log from the
--          aggregation (its minutes come back through the absence record, so
--          counting the log too would price the day twice and earn it phantom
--          overtime);
--        · the shift-absence sync, which must not read a full-day log as work;
--        · the worker's calendar, which draws such a day as its category alone
--          — no "06:00–14:00 · ceo dan", because the whole day IS the one thing.
--   2. Technical products + operations for GO / B / BP / B30, the way V25 made
--      them for NO / ND: a full-day absence is drawn on the shift as one work
--      log spanning it, and work_logs.operation_id is NOT NULL, so each such
--      category needs exactly one operation to hang the log from.
--
-- WHY A FLAG AND NOT "type = 'ABSENCE'"
--   Not every absence is a whole day. A two-hour gap recorded through the
--   absence dialog is an ABSENCE too, and it is NOT drawn as a full-shift log —
--   it keeps its own from/to. The whole-day-ness is a separate fact, so it gets
--   a separate flag rather than being read off the type.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Categories: the six full-day rows are stamped true by category_no; every
--   other category stays false, which is exactly the behaviour they have today
--   (only NO/ND were ever drawn as logs, and only they were filtered). Nothing
--   a report already holds moves — the flag changes how NEW full-day shifts are
--   drawn and how their logs are read, not any figure already computed.
--
--   Operations: created only where an active one does not already exist for the
--   category, so a database that already carries them (the live one does — GO,
--   B, BP, B30 each already have their single operation) is left untouched. A
--   fresh schema gets them.
--
--   · Rollback: ALTER TABLE work_code_categories DROP COLUMN is_full_day;
--     (the technical products/operations are harmless if left; they mirror the
--      NO/ND ones V25 created and are guarded the same way.)
-- =============================================================================

ALTER TABLE work_code_categories
    ADD COLUMN is_full_day boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN work_code_categories.is_full_day IS
    'TRUE when this category always stands for a whole shift nobody worked (GO, bolovanje, NO, ND): drawn as one full-shift log, dropped from the recalc aggregation, shown on the calendar without times.';

UPDATE work_code_categories
   SET is_full_day = true
 WHERE category_no IN ('GO', 'B', 'BP', 'B30', 'NO', 'ND')
   AND is_full_day = false;

-- Technical product per full-day absence category that has no operation yet.
INSERT INTO public.products (product_name, product_code, description)
SELECT v.product_name, v.product_code,
       'Technical product. Exists so the full-day absence operation has one; nothing is produced against it.'
FROM (VALUES
        ('Godišnji odmor', 'GO'),
        ('Bolovanje', 'B'),
        ('Bolovanje povreda na radu', 'BP'),
        ('Bolovanje preko 30 dana', 'B30')
     ) AS v(product_name, product_code)
WHERE NOT EXISTS (
    SELECT 1 FROM public.operations o
    JOIN public.work_code_categories c ON c.id = o.work_code_category_id
    WHERE c.category_no = v.product_code
      AND o.is_active = true AND o.archived_at IS NULL
)
AND NOT EXISTS (
    SELECT 1 FROM public.products p WHERE p.product_code = v.product_code
);

INSERT INTO public.operations
    (product_id, op_name, work_code_category_id, norm_required, is_active, description)
SELECT p.id, v.op_name, c.id, false, true,
       'The single operation a full-day absence log hangs from; work_logs.operation_id is NOT NULL.'
FROM (VALUES
        ('GO', 'Godišnji odmor'),
        ('B', 'Bolovanje'),
        ('BP', 'Bolovanje povreda na radu'),
        ('B30', 'Bolovanje preko 30 dana')
     ) AS v(category_no, op_name)
JOIN public.products p ON p.product_code = v.category_no
CROSS JOIN LATERAL (
    SELECT id FROM public.work_code_categories
    WHERE category_no = v.category_no AND archived_at IS NULL
    ORDER BY valid_from DESC NULLS LAST
    LIMIT 1
) c
WHERE NOT EXISTS (
    SELECT 1 FROM public.operations o
    JOIN public.work_code_categories wc ON wc.id = o.work_code_category_id
    WHERE wc.category_no = v.category_no
      AND o.is_active = true AND o.archived_at IS NULL
);
