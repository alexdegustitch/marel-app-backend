-- =============================================================================
-- A manufacturing time can carry notes
-- =============================================================================
-- WHAT CHANGES
--   Two nullable text columns, both a NOTE somebody typed:
--
--     product_manufacturing_times.note            one note for the whole record
--     product_manufacturing_time_operations.note  one note per operation line
--
-- WHY THEY ARE SEPARATE
--   They answer different questions and print in different places. The record's
--   note stands above the operations table — it is about the calculation as a
--   whole. A line's note is about that operation, and the report gathers them
--   under "Napomene" beneath the table, naming the operation each belongs to.
--
-- WHY THEY ARE STORED WITH THE RECORD AND NOT ON THE OPERATION
--   The same reason every other value here is snapshotted: a saved manufacturing
--   time is what was decided that day. A note explaining why a line was left out,
--   or why a norm was read out of the recorded work, belongs to THAT calculation
--   and must not change when the operation is edited later.
--
-- MIGRATION IMPACT
--   · Additive. Two nullable columns, no default, no constraint beyond the type.
--   · Every existing record and line stays NULL, and nothing prints where there
--     is nothing to print — the report's "Napomene" section is drawn only when
--     at least one line has one.
--   · Rollback is dropping the two columns.
-- =============================================================================

ALTER TABLE product_manufacturing_times
    ADD COLUMN IF NOT EXISTS note text;

ALTER TABLE product_manufacturing_time_operations
    ADD COLUMN IF NOT EXISTS note text;
