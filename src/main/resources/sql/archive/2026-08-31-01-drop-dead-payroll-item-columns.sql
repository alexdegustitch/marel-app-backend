-- =============================================================================
-- Four columns on payroll_run_items that nothing reads
-- =============================================================================
-- Each of these is written and never consulted — not by a calculator, not by a
-- total, not by a single screen. They are not the meal/transport/bonus families,
-- which still hold override state and go in phase 7; these carry nothing at all.
--
--   adjustment_amount                set to 0.00 when a run is initialised and
--                                    never touched again. A leftover from before
--                                    payroll_adjustments existed, when the sum of
--                                    adjustments was one number on the item. It is
--                                    SUM(payroll_adjustments.amount) now.
--
--   transport_allowance_days         written by the transport calculator, read by
--   transport_allowance_unit_amount  nobody and shown nowhere. The line carries
--                                    both figures as system_quantity and
--                                    system_unit_amount, which is where the
--                                    payslip reads them from.
--
--   last_calculated_at               written, never read, never displayed. What it
--                                    was meant to answer, payroll_adjustments
--                                    .calculated_at now answers per line, and
--                                    updated_at covers the row.
--
-- Verified against the whole codebase rather than from memory: no getter outside
-- the entity and the response DTO, and no reference in any component — only in the
-- TypeScript type and test fixtures, which go with them.
--
-- NOT INCLUDED, deliberately:
--   total_gross_earnings     is never computed and is 0.00 in every row — but the
--                            payslip screen prints it as "Ukupna zarada (+)".
--                            Dropping it silently changes what a screen shows, and
--                            what "gross" should mean here has not been decided.
--   total_deductions_amount  is computed correctly from the DEDUCTION_MINUS lines.
--                            Nothing displays it yet, which is an argument for
--                            showing it, not for deleting a correct figure.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE payroll_run_items
    DROP COLUMN IF EXISTS adjustment_amount,
    DROP COLUMN IF EXISTS transport_allowance_days,
    DROP COLUMN IF EXISTS transport_allowance_unit_amount,
    DROP COLUMN IF EXISTS last_calculated_at;

DO $$
BEGIN
    RAISE NOTICE 'payroll_run_items is down to % columns.',
        (SELECT count(*) FROM information_schema.columns
         WHERE table_name = 'payroll_run_items');
END $$;
