-- =============================================================================
-- A user account can BE one of the workers
-- =============================================================================
-- WHAT CHANGES
--   users.employee_id — nullable FK to employees(id), unique where present.
--
-- WHY AT ALL
--   Nothing in this schema said that the person who signs in and the person on
--   the payroll are the same person. `users` and `employees` were two unrelated
--   tables that happen to hold names. So "show me MY payslips" was not a
--   question the data could answer: there was no way to get from a session to a
--   worker.
--
--   It is a decision somebody makes, not something to infer. Matching on the
--   e-mail address was the alternative and it is not one: employees.email is
--   nullable, is not unique, and most workers have none. That would have failed
--   silently for the majority and, worse, occasionally succeeded on the wrong
--   row. An administrator states the link; the database keeps it honest.
--
-- WHY NULLABLE
--   Most accounts are NOT workers — administration, payroll, the developer
--   account. NULL is their correct and permanent answer, not a gap waiting to be
--   filled, so a NOT NULL column would be a lie with a placeholder in it.
--
-- WHY THE UNIQUE INDEX IS PARTIAL
--   "Whose payslip is this" must have exactly one answer, so one worker may have
--   at most one account. A plain UNIQUE would work in PostgreSQL (NULLs do not
--   collide), but stating WHERE employee_id IS NOT NULL says the intent out loud:
--   any number of accounts may be unlinked, and no two may claim the same worker.
--
-- WHY NO ON DELETE CLAUSE
--   Employees are archived (archived_at), never deleted — the rest of this schema
--   is built on that. NO ACTION is therefore the protective default: if anybody
--   ever does try to delete a worker who has an account, the database stops them
--   rather than quietly cutting a person loose from their payslips.
--
-- MIGRATION IMPACT
--   · Additive only. No existing table, column, constraint or trigger is touched
--     and no existing row changes: every account starts NULL, meaning "not a
--     worker".
--   · No query breaks. Nothing selected users.* into a positional structure, and
--     the new column is simply absent from every existing statement.
--   · Nothing reads it until an administrator sets one. Until then the profile's
--     payslip section says the account is not linked to a worker, rather than
--     showing an empty list that would read as "you have no payslips".
--   · Every step is guarded, so replaying the migration is safe.
--   · Rollback: DROP INDEX uq_users_employee_id; ALTER TABLE users DROP COLUMN
--     employee_id. Nothing else depends on it.
-- =============================================================================

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS employee_id bigint;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_users_employee'
    ) THEN
        ALTER TABLE public.users
            ADD CONSTRAINT fk_users_employee
            FOREIGN KEY (employee_id) REFERENCES public.employees(id);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_employee_id
    ON public.users (employee_id)
    WHERE employee_id IS NOT NULL;

COMMENT ON COLUMN public.users.employee_id IS
    'The worker this account belongs to, when it belongs to one. NULL means the account is not a worker (administration, payroll). At most one account per worker.';
