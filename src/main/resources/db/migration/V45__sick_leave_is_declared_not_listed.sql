-- =============================================================================
-- Sick leave is declared on the category, not listed in a setting
-- =============================================================================
-- WHAT CHANGES
--   The app_settings key `sick_leave_work_code_category_nos` is archived. It
--   predates V39: back then nothing in the schema said which categories mean
--   sick leave, so the board's "odsutni danas" card read a comma-separated
--   code list an administrator had to maintain. V39 declared it on the
--   category itself (work_code_categories.type = 'SICK_LEAVE',
--   sick_leave_kind), and the board now reads that declaration — plus GO —
--   directly.
--
-- WHY ARCHIVE RATHER THAN DELETE
--   The row is part of the settings history an administrator can read, and
--   history is not cleaned up here. Archived, it stops appearing among the
--   live parameters, which is the whole point: a setting nothing reads is a
--   setting somebody will one day edit and wonder why nothing happened.
--
-- WHAT HAPPENS TO EXISTING DATA
--   The row (all its validity versions) gets archived_at and leaves the live
--   list. No table shape changes.
--
-- Rollback: UPDATE app_settings SET archived_at = NULL
--           WHERE setting_key = 'sick_leave_work_code_category_nos';
-- =============================================================================

UPDATE app_settings
SET archived_at = now(),
    updated_at  = now()
WHERE setting_key = 'sick_leave_work_code_category_nos'
  AND archived_at IS NULL;
