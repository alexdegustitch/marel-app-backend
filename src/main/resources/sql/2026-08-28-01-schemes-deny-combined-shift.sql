-- =============================================================================
-- "I, II i III smena" belongs to the scheme that remaps into it, and to no other
-- =============================================================================
-- WHAT WAS WRONG
-- Work category S — "I, II i III smena" — is the TARGET of the fixed-coefficient
-- remap: under FOREIGN_FIXED_COEFFICIENT the eight real shift categories (D, DB,
-- J, JB, L, L3, LP, LP3) all calculate as S, and it is the one that earns a
-- payslip row there.
--
-- STANDARD and COMMERCIAL have no rule for it, and allow_unmapped_categories is
-- TRUE for both — the deliberate choice that an unlisted category is allowed
-- rather than silently dropped. So S was allowed there too, and
-- reconcileItemCategories gave every such employee a row for it: a permanent
-- empty line for a category they never book against, because they book on J and D.
--
-- This is the mirror of a case already handled. PayrollSchemeScopeService drops a
-- SOURCE that remaps elsewhere, so J does not appear for a foreign employee — no
-- work can land on it. Nothing said the reverse: that the TARGET has no business
-- appearing for a scheme that never remaps into it.
--
-- WHY A LIST AND NOT A DERIVED SET
-- It would be possible to compute "every scheme that does not remap into S" and
-- deny it there. That would also silently answer the question for a scheme
-- somebody adds next year, which is exactly the kind of guess the D-rules exist
-- to prevent — a new worker type decides for itself what it pays. The two schemes
-- named here are the two that exist and have been asked about.
--
-- WHAT THIS DOES NOT DO
-- It does not delete existing rows. getDetails filters on "allowed by the scheme
-- OR has activity", so they stop being displayed while the data stays
-- recoverable — and if such an employee ever does book time on S, the row
-- reappears with its minutes rather than hiding recorded work.
--
-- Supersedes 2026-08-28-01-standard-scheme-denies-combined-shift.sql, which did
-- the same for STANDARD alone. Re-running that file is harmless; this one covers
-- it. Re-runnable.
-- =============================================================================

DO $$
DECLARE
    v_scheme_code TEXT;
    v_scheme_id   BIGINT;
    v_category_id BIGINT;
    v_activity    INTEGER;
    v_added       INTEGER := 0;
BEGIN
    -- The work categories are DATA, not schema. A fresh database — the one the
    -- integration tests build from these very scripts, and any other factory
    -- installing this app — has none of them, and "S is not there" is an ordinary
    -- state with nothing to deny. Raising here would fail the schema build for
    -- everyone who is not this factory.
    SELECT id INTO v_category_id FROM work_code_categories WHERE category_no = 'S';
    IF v_category_id IS NULL THEN
        RAISE NOTICE 'No work category "S" in this database; nothing to deny.';
        RETURN;
    END IF;

    FOREACH v_scheme_code IN ARRAY ARRAY['STANDARD', 'COMMERCIAL'] LOOP
        SELECT id INTO v_scheme_id FROM compensation_schemes WHERE code = v_scheme_code;
        IF v_scheme_id IS NULL THEN
            RAISE NOTICE 'Compensation scheme % does not exist here; skipped.', v_scheme_code;
            CONTINUE;
        END IF;

        -- Refuse to hide recorded work. If an employee on this scheme has minutes
        -- on S then the premise is wrong and somebody has to look at it, rather
        -- than have it quietly dropped from a payslip.
        SELECT count(*) INTO v_activity
        FROM payroll_run_item_categories c
        JOIN payroll_run_items i ON i.id = c.payroll_run_item_id
        JOIN employee_compensation_scheme_history h
             ON h.employee_id = i.employee_id
            AND h.archived_at IS NULL
            AND h.compensation_scheme_id = v_scheme_id
            AND h.valid_from <= i.period
            AND (h.valid_until IS NULL OR h.valid_until >= i.period)
        WHERE c.work_code_category_id = v_category_id
          AND COALESCE(c.total_minutes, 0) + COALESCE(c.effective_minutes, 0) > 0;

        IF v_activity > 0 THEN
            RAISE EXCEPTION 'Category "S" carries real minutes on % payroll item(s) under %. '
                'They would keep displaying (activity always wins over the scheme), but the '
                'premise here is that these employees never book against S. Check those rows '
                'before applying.', v_activity, v_scheme_code;
        END IF;

        -- valid_from matches the existing rules: 2020-01-01, comfortably before
        -- the earliest scheme period, so no month is left uncovered.
        IF NOT EXISTS (
            SELECT 1 FROM work_code_category_scheme_rules
            WHERE compensation_scheme_id = v_scheme_id
              AND source_category_id = v_category_id
              AND archived_at IS NULL
        ) THEN
            INSERT INTO work_code_category_scheme_rules
                (compensation_scheme_id, source_category_id, effective_category_id,
                 is_allowed, is_selectable, valid_from, note)
            VALUES
                (v_scheme_id, v_category_id, NULL,
                 FALSE, FALSE, DATE '2020-01-01',
                 'S je cilj remapiranja za FOREIGN_FIXED_COEFFICIENT. Radnici na ovom nacinu obracuna evidentiraju rad na J i D, pa je ovde prazan red na listicu.');
            v_added := v_added + 1;
            RAISE NOTICE '% now explicitly denies work category S.', v_scheme_code;
        ELSE
            RAISE NOTICE '% already has a rule for work category S; left as it is.', v_scheme_code;
        END IF;
    END LOOP;

    RAISE NOTICE '% rule(s) added. % existing row(s) on S stop being displayed for those '
        'schemes; none are deleted.',
        v_added,
        (SELECT count(*) FROM payroll_run_item_categories WHERE work_code_category_id = v_category_id);
END $$;
