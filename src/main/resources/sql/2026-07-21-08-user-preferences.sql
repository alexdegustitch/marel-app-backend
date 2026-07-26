-- =============================================================================
-- User preferences, per-table preferences and saved views
-- =============================================================================
-- Three tables with three separate responsibilities that must not be merged:
--
--   user_preferences        global, user-level appearance and behaviour
--   user_table_preferences  one dense table's column/sort/width layout
--   user_saved_views        reusable named filter + display configurations
--
-- Settings that the backend reads, validates or may query are typed columns.
-- Only free-form, visual-only extras live in JSONB. "Everything in one JSON
-- blob" is explicitly avoided.
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_preferences (
    -- One row per user: the user IS the key. No surrogate id, so a second row
    -- for the same user is structurally impossible.
    user_id                     BIGINT      NOT NULL PRIMARY KEY,
    theme                       VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    language                    VARCHAR(10) NOT NULL DEFAULT 'sr',
    timezone                    VARCHAR(64) NOT NULL DEFAULT 'Europe/Belgrade',
    date_format                 VARCHAR(32) NOT NULL DEFAULT 'dd.MM.yyyy',
    time_format                 VARCHAR(32) NOT NULL DEFAULT 'HH:mm',
    number_format               VARCHAR(32) NOT NULL DEFAULT 'sr-RS',
    ui_density                  VARCHAR(20) NOT NULL DEFAULT 'COMFORTABLE',
    rows_per_page               INTEGER     NOT NULL DEFAULT 25,
    sidebar_collapsed           BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Backend behaviour depends on these two, which is exactly why they are
    -- typed columns and not JSON keys: the notification fan-out reads them when
    -- deciding whether to create an EMAIL delivery for a user.
    email_notifications_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    in_app_notifications_enabled BOOLEAN    NOT NULL DEFAULT TRUE,
    -- Visual-only extras the backend never interprets. Size- and shape-checked
    -- by UserPreferencesService before write.
    ui_settings                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ,
    version                     BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT fk_user_preferences_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT chk_user_preferences_theme
        CHECK (theme IN ('SYSTEM', 'LIGHT', 'DARK')),
    CONSTRAINT chk_user_preferences_ui_density
        CHECK (ui_density IN ('COMPACT', 'COMFORTABLE', 'SPACIOUS')),
    CONSTRAINT chk_user_preferences_rows_per_page
        CHECK (rows_per_page BETWEEN 5 AND 500),
    -- A JSON object, not an array or scalar, and bounded so a client cannot use
    -- preferences as free storage.
    CONSTRAINT chk_user_preferences_ui_settings
        CHECK (jsonb_typeof(ui_settings) = 'object'
               AND pg_column_size(ui_settings) <= 16384)
);

DROP TRIGGER IF EXISTS trg_03_user_preferences_updated_at ON user_preferences;
CREATE TRIGGER trg_03_user_preferences_updated_at
    BEFORE UPDATE ON user_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE user_preferences IS
    'One row per user. Created lazily on first read (see UserPreferencesService.getOrCreateForUser); ON DELETE CASCADE because preferences have no meaning without the user.';


-- =============================================================================
-- Per-table layout preferences
-- =============================================================================
CREATE TABLE IF NOT EXISTS user_table_preferences (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    -- Validated against an application-side registry (TableKey enum). It is a
    -- display key only and is NEVER interpolated into SQL as an identifier.
    table_key  VARCHAR(80) NOT NULL,
    settings   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,

    CONSTRAINT fk_user_table_preferences_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT chk_user_table_preferences_table_key
        CHECK (length(trim(table_key)) > 0),

    CONSTRAINT chk_user_table_preferences_settings
        CHECK (jsonb_typeof(settings) = 'object'
               AND pg_column_size(settings) <= 32768)
);

-- At most one preference row per user per table.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_table_preferences_user_table
    ON user_table_preferences (user_id, table_key);

DROP TRIGGER IF EXISTS trg_03_user_table_preferences_updated_at ON user_table_preferences;
CREATE TRIGGER trg_03_user_table_preferences_updated_at
    BEFORE UPDATE ON user_table_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE user_table_preferences IS
    'Per-user column/sort/width layout for one dense table. Purely presentational: it never affects authorization or backend filtering.';


-- =============================================================================
-- Saved views
-- =============================================================================
CREATE TABLE IF NOT EXISTS user_saved_views (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    view_key    VARCHAR(80)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    -- Structured filter/sort/column descriptors, validated field-by-field
    -- against the same registry the list endpoints use. Never an SQL fragment:
    -- values are bound as parameters, never concatenated.
    filters     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    sorting     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    columns     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,

    CONSTRAINT fk_user_saved_views_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT chk_user_saved_views_name
        CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_user_saved_views_view_key
        CHECK (length(trim(view_key)) > 0),

    CONSTRAINT chk_user_saved_views_filters
        CHECK (jsonb_typeof(filters) = 'object' AND pg_column_size(filters) <= 32768),
    CONSTRAINT chk_user_saved_views_sorting
        CHECK (jsonb_typeof(sorting) = 'array' AND pg_column_size(sorting) <= 8192),
    CONSTRAINT chk_user_saved_views_columns
        CHECK (jsonb_typeof(columns) = 'array' AND pg_column_size(columns) <= 8192),

    -- An archived view cannot also be the default.
    CONSTRAINT chk_user_saved_views_default_not_archived
        CHECK (NOT (is_default AND archived_at IS NOT NULL))
);

-- At most one active default per user per view key. Enforced in the database so
-- the "unset the old default, set the new one" transaction cannot race into two
-- defaults.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_saved_views_one_default
    ON user_saved_views (user_id, view_key)
    WHERE is_default AND archived_at IS NULL;

-- Distinct active view names per user per key, case-insensitively.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_saved_views_name_active
    ON user_saved_views (user_id, view_key, lower(name))
    WHERE archived_at IS NULL;

-- The list endpoint: a user's active views for one screen.
CREATE INDEX IF NOT EXISTS idx_user_saved_views_user_key_active
    ON user_saved_views (user_id, view_key)
    WHERE archived_at IS NULL;

DROP TRIGGER IF EXISTS trg_03_user_saved_views_updated_at ON user_saved_views;
CREATE TRIGGER trg_03_user_saved_views_updated_at
    BEFORE UPDATE ON user_saved_views
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE user_saved_views IS
    'Named, reusable filter/sort/column configurations owned by one user. Saved views never widen what a user is allowed to see.';
