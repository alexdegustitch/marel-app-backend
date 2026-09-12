-- =============================================================================
-- A work-code category can carry a colour and a pattern
-- =============================================================================
-- WHAT CHANGES
--   · work_code_categories.color   (new, nullable): the swatch a category is
--     drawn with on the karton's shift timeline — a CSS hex like '#3b82f6'
--     or '#3b82f6cc'. NULL means "no colour chosen"; the client then falls
--     back to a stable colour derived from the category code, so an untouched
--     category keeps looking exactly as it does today.
--   · work_code_categories.pattern (new, NOT NULL default 'NONE'): how the
--     bar is filled — 'NONE' (solid), 'CHECKER' (šahovska), or 'STRIPES'
--     (pruge, the fill an unpaid absence and a non-working day already use).
--
-- WHY THESE ARE COSMETIC, NOT VERSIONED VALUES
--   A category is re-versioned over time (same category_no, new row, adjacent
--   validity windows) whenever a CALCULATION value moves — the norm multiplier,
--   the paid flag, the hourly rate. Colour and pattern move none of those:
--   they are the same kind of thing as the category's name and note, which the
--   admin service already treats as cosmetic (applied in place, never minting a
--   version). So changing a colour must NOT spin a new version, and the service
--   sets these next to name/note rather than through the versioned-value path.
--   Because the appearance is authored once and carried forward onto every new
--   version, the whole chain of a code reads as one colour, which is what "each
--   category type has its own colour" means.
--
-- WHY NOTHING RECOMPUTES
--   Payroll, norms, the recalc and the reports never read these columns —
--   appearance is presentation only. The change is purely additive: existing
--   rows get color = NULL and pattern = 'NONE', and every historical payroll
--   and karton reads back exactly as before until a colour is chosen.
-- =============================================================================

ALTER TABLE work_code_categories
    ADD COLUMN color VARCHAR(9),
    ADD COLUMN pattern VARCHAR(16) NOT NULL DEFAULT 'NONE';

ALTER TABLE work_code_categories
    ADD CONSTRAINT ck_work_code_categories_pattern
        CHECK (pattern IN ('NONE', 'CHECKER', 'STRIPES'));
