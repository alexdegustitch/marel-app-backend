-- Add optional note field to work_code_categories
ALTER TABLE work_code_categories
    ADD COLUMN IF NOT EXISTS note TEXT NULL;

