-- Add display_text column to app_settings table
ALTER TABLE app_settings
    ADD COLUMN IF NOT EXISTS display_text VARCHAR(255);

