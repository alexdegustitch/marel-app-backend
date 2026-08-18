-- =============================================================================
-- What was in the handover, line by line
-- =============================================================================
-- THE CHANGE
-- payroll_run_item_handovers recorded the two totals. The owner's answer to
-- "what does payroll see of a handover" is: the sum AND every line, as the
-- supervisor handed them over. Two totals cannot answer "which line moved
-- after I submitted", which is the question an argument actually turns on.
--
-- WHY jsonb AND NOT A SECOND TABLE
-- Measured, not assumed. In this database calculation_inputs averages 43 bytes
-- across 13.967 rows — 580 kB in a 7.6 MB table. A snapshot payload of ~13
-- lines costs roughly 700 bytes; a normalised line table would cost MORE for
-- the same content, because each row pays a 23-byte tuple header plus its index
-- entry before any data. At a few thousand handovers a year this is single-digit
-- megabytes either way, and the jsonb version does not need a migration every
-- time somebody adds an adjustment category.
--
-- The two figures that get QUERIED (compare handed-over against paid) stay as
-- real columns, so no GIN index over the payload is ever needed — that index,
-- not the data, is where jsonb actually gets expensive.
--
-- SHORT KEYS ON PURPOSE
-- jsonb repeats key names in every row. c/a/q/u rather than
-- categoryCode/amount/quantity/unitAmount, for the same reason the columns
-- beside them are not called payrollRunItemIdentifier.
--
-- IMPACT
-- One nullable column on a table that currently has no rows in production.
-- Nothing to backfill. Reversible with DROP COLUMN.
-- =============================================================================

ALTER TABLE payroll_run_item_handovers
    ADD COLUMN payload JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN payroll_run_item_handovers.payload IS
    'The lines as handed over: {"lines":[{"c":code,"a":amount,"q":qty,"u":unit}]}. '
    'A record of that moment, never recalculated.';
