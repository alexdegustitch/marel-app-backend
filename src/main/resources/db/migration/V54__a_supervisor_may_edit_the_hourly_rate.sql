-- The supervisor edits the hourly rate, out of the box (owner ask, 2026-09-12).
--
-- The payroll access matrix defaults every field to NO for roles outside
-- payroll, which is right for money lines the shop floor should not touch —
-- but the hourly rate is the one figure the supervisor sets while preparing a
-- month, and every fresh install answered their edit with a 409 until an
-- administrator found the matrix. This seeds that one decision; it remains an
-- ordinary matrix row, changeable any time on Podešavanja → Pristup obračunu.
--
-- Upsert, not insert: an installation where the administrator already touched
-- this cell gets the grant applied the same way the settings screen would.
INSERT INTO payroll_field_access (field_code, role_name, can_view, can_edit)
VALUES ('HOURLY_RATE', 'supervisor', true, true)
ON CONFLICT ON CONSTRAINT uq_pfa_field_role
DO UPDATE SET can_view = true, can_edit = true, updated_at = now();
