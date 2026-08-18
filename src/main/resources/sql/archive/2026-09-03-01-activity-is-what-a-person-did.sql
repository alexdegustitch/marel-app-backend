-- =============================================================================
-- "Poslednja aktivnost" must mean somebody did something
-- =============================================================================
-- WHAT WAS WRONG
-- trg_payroll_run_items_track_activity fired on EVERY insert, update and delete
-- of payroll_run_items and wrote a row into employee_payroll_run_item_updates.
-- It skipped writes with no app.user_id set, which was meant to exclude system
-- work — and does exclude the background recalc worker.
--
-- It does not exclude the case that matters. Recalculation is LAZY: opening an
-- employee's payroll calls getForPayrollAccess, which recomputes a stale item and
-- UPDATEs it, inside the reader's own request. AuditUserAspect has set
-- app.user_id by then, because the reader is logged in. So changing one bonus
-- eligibility rule — which bumps the monthly report version and makes every item
-- of that month stale — turned into "user X edited this payroll" for every item
-- that user afterwards so much as LOOKED at.
--
-- 560 of 849 items carry an activity row in this database. Nobody edited 560
-- payrolls.
--
-- WHY THE TRIGGER CANNOT BE SAVED
-- At flush time there is nothing to tell the two apart. A patch and the
-- recalculation it triggers land in the same UPDATE on the same row, so no column
-- test and no session flag set around the recalculation can separate "the
-- administrator changed the meal price" from "the calculation rewrote the item on
-- the way past". Only the CALLER knows, so only the caller can say.
--
-- WHAT REPLACES IT
-- PayrollRunItemService records activity explicitly on the operations that are
-- somebody's decision: patch, lock and unlock. Reading a payroll — even when that
-- read recalculates — records nothing.
--
-- THE EXISTING ROWS ARE LEFT ALONE. Most are false, but there is no way to tell
-- which: a real edit and a lazy recalculation produced identical rows. Deleting
-- them all would throw away the true ones with them, and keeping them costs
-- nothing but a stale timestamp that the next real edit overwrites.
--
-- work_shifts and work_logs keep their triggers. A row changing there IS a
-- person's doing; the recalculation that touches work_shifts runs in the
-- background worker, with no app.user_id, and is already skipped.
--
-- Re-runnable.
-- =============================================================================

DROP TRIGGER IF EXISTS trg_payroll_run_items_track_activity ON payroll_run_items;

DO $$
DECLARE
    v_rows INTEGER;
BEGIN
    SELECT count(*) INTO v_rows FROM employee_payroll_run_item_updates;
    RAISE NOTICE 'Activity is now recorded by the service, on edits only. % existing row(s) '
        'are left as they are — a real edit and a lazy recalculation wrote the same row, so '
        'there is nothing to tell them apart by.', v_rows;
END $$;
