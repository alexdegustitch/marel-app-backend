-- =============================================================================
-- User-managed mailing lists
-- =============================================================================
-- A mailing list is a reusable, named set of recipients owned by the user who
-- created it. A member is EITHER an application user (user_id) OR an external
-- person known only by an email address (external_email) — never both, never
-- neither.
--
-- Email case-insensitivity follows the convention already established by
-- users.uq_users_email_address_ci: a functional unique index over
-- lower(<column>). CITEXT is deliberately not introduced — the extension is
-- available but unused, and mixing strategies would be worse than following the
-- existing one.
-- =============================================================================

CREATE TABLE IF NOT EXISTS mailing_lists (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    description   VARCHAR(1000),
    owner_user_id BIGINT       NOT NULL,
    visibility    VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    archived_at   TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_mailing_lists_owner_user_id
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,

    -- PRIVATE  : owner only
    -- SHARED   : owner + users explicitly granted access via mailing_list_access
    -- GLOBAL   : every user who holds the mailing-list read permission
    CONSTRAINT chk_mailing_lists_visibility
        CHECK (visibility IN ('PRIVATE', 'SHARED', 'GLOBAL')),

    CONSTRAINT chk_mailing_lists_name
        CHECK (length(trim(name)) > 0)
);

-- Name uniqueness is scoped to the owner and only applies while the list is
-- active — an archived list must not block reusing its name, and two different
-- owners may each have a "Kupci" list. Case-insensitive so "Kupci" and "kupci"
-- collide.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mailing_lists_owner_name_active
    ON mailing_lists (owner_user_id, lower(name))
    WHERE archived_at IS NULL;

-- "my lists" / "lists I own", active only.
CREATE INDEX IF NOT EXISTS idx_mailing_lists_owner_active
    ON mailing_lists (owner_user_id)
    WHERE archived_at IS NULL;

-- Resolving GLOBAL/SHARED lists visible to a user without scanning archived rows.
CREATE INDEX IF NOT EXISTS idx_mailing_lists_visibility_active
    ON mailing_lists (visibility)
    WHERE archived_at IS NULL;

DROP TRIGGER IF EXISTS trg_03_mailing_lists_updated_at ON mailing_lists;
CREATE TRIGGER trg_03_mailing_lists_updated_at
    BEFORE UPDATE ON mailing_lists
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE mailing_lists IS
    'Reusable recipient list owned by a user. Archived (archived_at) rather than deleted so production-order history stays intact.';


-- =============================================================================
-- Shared-list access grants
-- =============================================================================
-- No generic resource-permission mechanism exists in this codebase (authorization
-- is role-based only), so SHARED visibility needs its own explicit grant table.
CREATE TABLE IF NOT EXISTS mailing_list_access (
    mailing_list_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    granted_by      BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_mailing_list_access PRIMARY KEY (mailing_list_id, user_id),

    CONSTRAINT fk_mailing_list_access_mailing_list_id
        FOREIGN KEY (mailing_list_id) REFERENCES mailing_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_mailing_list_access_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mailing_list_access_granted_by
        FOREIGN KEY (granted_by) REFERENCES users (id) ON DELETE RESTRICT
);

-- "which shared lists can this user see" — the access check on every list read.
CREATE INDEX IF NOT EXISTS idx_mailing_list_access_user_id
    ON mailing_list_access (user_id);

COMMENT ON TABLE mailing_list_access IS
    'Explicit per-user grants for SHARED mailing lists. Cascades with the list because a grant has no meaning without it.';


-- =============================================================================
-- Mailing-list members
-- =============================================================================
CREATE TABLE IF NOT EXISTS mailing_list_members (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mailing_list_id BIGINT       NOT NULL,
    user_id         BIGINT,
    external_email  VARCHAR(320),
    display_name    VARCHAR(150),
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    archived_at     TIMESTAMPTZ,

    CONSTRAINT fk_mailing_list_members_mailing_list_id
        FOREIGN KEY (mailing_list_id) REFERENCES mailing_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_mailing_list_members_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mailing_list_members_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,

    -- Exactly one member source. Never both, never neither.
    CONSTRAINT chk_mailing_list_members_exactly_one_source
        CHECK (
            (user_id IS NOT NULL AND external_email IS NULL)
            OR (user_id IS NULL AND external_email IS NOT NULL)
        ),

    -- An external address must be a plausible single address. The '%_@_%._%'
    -- shape plus the CR/LF ban blocks SMTP header injection at the database
    -- level, not only in bean validation.
    CONSTRAINT chk_mailing_list_members_external_email
        CHECK (
            external_email IS NULL
            OR (
                external_email = lower(trim(external_email))
                AND external_email LIKE '%_@_%._%'
                AND external_email !~ '[[:space:]]'
            )
        ),

    CONSTRAINT chk_mailing_list_members_display_name
        CHECK (display_name IS NULL OR length(trim(display_name)) > 0)
);

-- The same application user cannot be an active member twice.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mailing_list_members_user_active
    ON mailing_list_members (mailing_list_id, user_id)
    WHERE user_id IS NOT NULL AND archived_at IS NULL;

-- The same external address cannot be an active member twice. Stored already
-- lower-cased (enforced by the check above), indexed on lower() to match the
-- users.uq_users_email_address_ci convention.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mailing_list_members_email_active
    ON mailing_list_members (mailing_list_id, lower(external_email))
    WHERE external_email IS NOT NULL AND archived_at IS NULL;

-- NOTE ON THE REMAINING DUPLICATE CASE
-- An external_email that happens to equal some application user's current
-- email_address cannot be excluded by a database constraint, because a user
-- member's effective address lives in users.email_address and is intentionally
-- NOT snapshotted here (rule: if a user changes their email, the membership
-- keeps following the user). MailingListMemberService therefore performs that
-- cross-source check in the application, and the production-order recipient
-- snapshot deduplicates by normalized address anyway, so a slipped duplicate can
-- never produce two emails for one person.

-- Listing active members of a list — the hot path for building a snapshot.
CREATE INDEX IF NOT EXISTS idx_mailing_list_members_list_active
    ON mailing_list_members (mailing_list_id)
    WHERE archived_at IS NULL;

-- "which lists is this user on" (used when a user is suspended or archived).
CREATE INDEX IF NOT EXISTS idx_mailing_list_members_user_id
    ON mailing_list_members (user_id)
    WHERE user_id IS NOT NULL;

COMMENT ON TABLE mailing_list_members IS
    'Members of a mailing list: either an application user or an external email address. Removal is an archive (archived_at), never a delete.';
COMMENT ON COLUMN mailing_list_members.external_email IS
    'Normalized lower-case email for a non-application recipient. Mutually exclusive with user_id.';
