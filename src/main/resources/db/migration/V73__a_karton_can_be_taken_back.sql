-- =============================================================================
-- A karton can be taken back
-- =============================================================================
-- WHAT CHANGES
--   employee_records gains two nullable columns:
--
--       archived_by     bigint REFERENCES users (id)
--       archive_reason  varchar(255)
--
--   Nothing else. No existing row is touched, no column changes type, no
--   constraint is added to data that is already there — so this takes a brief
--   ACCESS EXCLUSIVE lock to write the catalogue entries and no table rewrite,
--   whatever the size of employee_records.
--
-- WHY
--   employee_records has carried `is_active` and `archived_at` since the V1
--   baseline, and until now NOTHING wrote them: createMonthlyRecord set
--   is_active = true, archived_at stayed NULL for every row ever created, and
--   not one query in EmployeeRecordRepository filtered on either. A karton
--   opened by mistake — wrong month, a worker who never started, a batch run
--   twice — could only be left standing.
--
--   The two columns here are what those existing ones were missing to be
--   usable as a real withdrawal: WHO took the karton back and WHY. That is the
--   same pair work_shifts already carries (archived_at / archived_by), and the
--   shape V39, V50, V57, V67, V68 and V69 all settled on.
--
-- WHAT IT IS NOT
--   · Not a delete. A karton that holds anything — shifts, a monthly report, a
--     payroll item — is never destroyed; archiving it leaves every row where it
--     was and merely stops it counting. Only a karton that never held anything
--     is deleted outright, and the database agrees: work_shifts,
--     monthly_reports and employee_record_updates all reference
--     employee_records without ON DELETE CASCADE, so a karton with children
--     cannot be erased even by mistake.
--   · Not a status. archived_at is the fact and is_active is kept in step with
--     it, exactly as on work_shifts, so queries that already read is_active
--     keep working unchanged.
--   · No backfill. Every existing karton stays live (archived_at IS NULL,
--     is_active = true), which is what it already was.
--
-- ROLLBACK
--   ALTER TABLE employee_records DROP COLUMN archive_reason;
--   ALTER TABLE employee_records DROP COLUMN archived_by;
--
--   Safe to run as long as no karton has been archived yet; afterwards it
--   drops the record of who withdrew one, while archived_at survives.
-- =============================================================================

ALTER TABLE employee_records
    ADD COLUMN archived_by    bigint REFERENCES users (id),
    ADD COLUMN archive_reason varchar(255);

COMMENT ON COLUMN employee_records.archived_by IS
    'Who withdrew this karton; NULL while it is live. Pairs with archived_at.';

COMMENT ON COLUMN employee_records.archive_reason IS
    'Why it was withdrawn, as the person typed it; NULL while it is live.';

-- The lists read one month at a time and want only the live kartoni of it.
-- Partial so it costs nothing to maintain for the overwhelming majority of
-- rows, which are live and already covered by the plain start_date index.
CREATE INDEX IF NOT EXISTS idx_employee_records_archived
    ON employee_records (start_date)
    WHERE archived_at IS NOT NULL;
