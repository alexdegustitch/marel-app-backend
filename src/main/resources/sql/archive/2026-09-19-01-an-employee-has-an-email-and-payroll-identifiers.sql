-- =============================================================================
-- An employee has an email, a JMBG and a bank account
-- =============================================================================
-- WHAT AND WHY
-- Three pieces of personnel data the table could not hold at all.
--
--   email         The only one the application will actually show and edit for
--                 now. Optional: not every factory worker has one.
--
--   jmbg          Needed before a payroll run can produce a tax filing. Stored
--                 now so the column exists and can be filled in gradually; the
--                 UI deliberately does NOT render it yet (owner's decision), so
--                 nothing reads it and nothing depends on it being present.
--
--   bank_account  Same reasoning — a salary cannot be paid without it, but it is
--                 not part of any screen yet.
--
-- ALL THREE ARE NULLABLE. 138 employees already exist and none of them has any
-- of these values; a NOT NULL column would have to invent them.
--
-- SENSITIVE DATA
-- jmbg and bank_account are personal identifiers. They are not exposed by any
-- DTO in this change. Whoever puts them on a screen later is the one who has to
-- decide who may see them — do not widen an existing employee DTO without that
-- decision, because the employee list is visible to every administrator.
--
-- CONSTRAINT CHOICES
--   jmbg    Exactly 13 digits (the JMBG format) and UNIQUE where present. Two
--           employee rows sharing a JMBG means the same person was entered
--           twice, which is worth refusing.
--   email   Only a token '@' sanity check, and NOT unique: relatives working at
--           the same factory sharing one address is ordinary, and a unique index
--           would reject it for no benefit.
--   bank_account  varchar(34) so an IBAN fits beside a domestic 18-digit
--           account. No format check — the written forms vary too much to pin
--           down without rejecting something valid.
--
-- Re-runnable.
-- =============================================================================

ALTER TABLE employees ADD COLUMN IF NOT EXISTS email        character varying(255);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS jmbg         character varying(13);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS bank_account character varying(34);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_email') THEN
        ALTER TABLE employees ADD CONSTRAINT chk_employees_email
            CHECK (email IS NULL OR (length(trim(both from email)) > 2 AND position('@' in email) > 1));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_jmbg') THEN
        ALTER TABLE employees ADD CONSTRAINT chk_employees_jmbg
            CHECK (jmbg IS NULL OR jmbg ~ '^[0-9]{13}$');
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_bank_account') THEN
        ALTER TABLE employees ADD CONSTRAINT chk_employees_bank_account
            CHECK (bank_account IS NULL OR length(trim(both from bank_account)) > 0);
    END IF;
END $$;

-- Partial: only rows that HAVE a JMBG are constrained, so the 138 existing NULLs
-- do not collide with each other.
CREATE UNIQUE INDEX IF NOT EXISTS ux_employees_jmbg
    ON employees (jmbg)
    WHERE jmbg IS NOT NULL;

COMMENT ON COLUMN employees.email IS
    'Optional contact address for the employee. Shown and edited in the employee screens.';

COMMENT ON COLUMN employees.jmbg IS
    'Personal identification number, 13 digits. Required before payroll can produce a tax filing. NOT exposed by any DTO yet — decide who may see it before putting it on a screen.';

COMMENT ON COLUMN employees.bank_account IS
    'Account a salary is paid into; wide enough for an IBAN. NOT exposed by any DTO yet — same access decision as jmbg applies.';
