-- first_name/last_name were added with a placeholder default ('hej') while being
-- backfilled by hand; existing rows already hold real values, so it's now safe to
-- drop the default and require every future insert to supply real names.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'first_name' AND column_default IS NOT NULL
    ) THEN
        ALTER TABLE users ALTER COLUMN first_name DROP DEFAULT;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'last_name' AND column_default IS NOT NULL
    ) THEN
        ALTER TABLE users ALTER COLUMN last_name DROP DEFAULT;
    END IF;
END $$;

-- full_name becomes a DB-derived column instead of an independently-set value, so it
-- can never drift out of sync with first_name/last_name. Existing rows already equal
-- first_name || ' ' || last_name exactly, so recomputing them is a no-op.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'full_name' AND is_generated = 'NEVER'
    ) THEN
        ALTER TABLE users DROP COLUMN full_name;
        ALTER TABLE users ADD COLUMN full_name TEXT
            GENERATED ALWAYS AS (first_name || ' ' || last_name) STORED NOT NULL;
    END IF;
END $$;
