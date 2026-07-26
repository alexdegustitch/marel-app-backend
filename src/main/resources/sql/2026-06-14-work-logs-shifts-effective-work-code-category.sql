-- Reversible bonus category remap.
-- Stores the bonus-effective work code category SEPARATELY from the original
-- (as-entered) work_code_category_id. The original column is never overwritten;
-- effective_work_code_category_id is recomputed on every recalc:
--   * set to the remapped (bonus) category when the night/weekend condition holds,
--   * set back to NULL when it no longer holds (automatic revert).
-- NULL means "no active bonus remap → use work_code_category_id".
-- Additive, nullable; no backfill needed. In dev, Hibernate ddl-auto=update also
-- creates these columns from the entity mappings on startup.

ALTER TABLE work_logs
    ADD COLUMN IF NOT EXISTS effective_work_code_category_id BIGINT NULL
        REFERENCES work_code_categories(id);

ALTER TABLE work_shifts
    ADD COLUMN IF NOT EXISTS effective_work_code_category_id BIGINT NULL
        REFERENCES work_code_categories(id);
