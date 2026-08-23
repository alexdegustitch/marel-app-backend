-- =============================================================================
-- Why a saved norm has no date
-- =============================================================================
-- WHAT CHANGES
--   product_manufacturing_time_operations gains norm_date_note: why the norm on
--   this line carries no date. Two reasons exist, and they are not the same:
--
--     TEMPORARY  the operation's norm in force was entered without a date on
--                purpose ("privremena").
--     ANALYTICS  the norm on this line was read out of the recorded work on the
--                manufacturing-time screen, so the work IS the date.
--
-- WHY IT HAS TO BE STORED
--   The record is a SNAPSHOT — it keeps the values used, not the operation they
--   came from, and it must, because the operation's norm changes afterwards.
--   Until now it kept the norm and the date but not the reason a date was
--   missing, so a report rebuilt from the record (the one a request hands out)
--   printed "–" where the screen had said "privremena" or "analitika". One
--   document said two different things depending on where it was generated.
--
-- MIGRATION IMPACT
--   · Additive. One nullable text column, constrained to the two words above.
--   · Existing lines stay NULL and keep printing "–". That is deliberate: a
--     record saved before this ran did not record why, and inventing a reason
--     for it would put a claim on paper that nobody made.
--   · No behaviour changes for any line that HAS a date; the column is only ever
--     read when there is none.
--   · Rollback is dropping the column.
-- =============================================================================

ALTER TABLE product_manufacturing_time_operations
    ADD COLUMN IF NOT EXISTS norm_date_note text;

ALTER TABLE product_manufacturing_time_operations
    DROP CONSTRAINT IF EXISTS chk_pmto_norm_date_note;
ALTER TABLE product_manufacturing_time_operations
    ADD CONSTRAINT chk_pmto_norm_date_note
        CHECK (norm_date_note IS NULL OR norm_date_note IN ('TEMPORARY', 'ANALYTICS'));
