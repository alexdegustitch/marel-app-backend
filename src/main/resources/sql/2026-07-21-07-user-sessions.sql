-- =============================================================================
-- User sessions and online presence
-- =============================================================================
-- An ACTIVE account and an ONLINE user are different things. account_status says
-- the user is ALLOWED to use the application; presence says at least one live
-- session reported activity recently. There is deliberately no users.is_online
-- boolean — a crashed client or a killed process would strand it as a permanent
-- lie.
--
-- Relationship to the existing auth model: refresh tokens rotate on every
-- refresh, so a refresh_tokens row is NOT a stable session identity — but
-- refresh_tokens.family_id already is: it is created once at login and carried
-- across every rotation of that login. user_sessions therefore keys on family_id.
--
-- No refresh_token_hash column is added here on purpose. refresh_tokens already
-- owns the hashes; copying them into a second table would duplicate
-- secret-derived material for no benefit. Raw refresh tokens are never stored
-- anywhere (RefreshTokenService hashes with SHA-256 before persisting).
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_sessions (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    -- Stable login identity, shared with refresh_tokens.family_id.
    family_id    VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    revoked_by   BIGINT,
    logout_at    TIMESTAMPTZ,
    device_name  VARCHAR(150),
    user_agent   VARCHAR(500),
    ip_address   VARCHAR(100),

    CONSTRAINT fk_user_sessions_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_sessions_revoked_by
        FOREIGN KEY (revoked_by) REFERENCES users (id) ON DELETE RESTRICT,

    -- Revocation is always attributable: revoked_by is the administrator, or the
    -- session's own user for a self-initiated logout.
    CONSTRAINT chk_user_sessions_revocation
        CHECK (
            (revoked_at IS NULL AND revoked_by IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL)
        )
);

-- One session row per login family. Also makes "create session at login"
-- idempotent under a retry.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_sessions_family_id
    ON user_sessions (family_id);

-- The presence query: live sessions for a user (or for a set of users, when the
-- UI shows who is online). Partial, because revoked sessions are dead weight.
CREATE INDEX IF NOT EXISTS idx_user_sessions_user_live
    ON user_sessions (user_id, last_seen_at DESC)
    WHERE revoked_at IS NULL;

-- Expired-session cleanup job.
CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at
    ON user_sessions (expires_at)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE user_sessions IS
    'One row per login (keyed by refresh_tokens.family_id). Presence is derived from last_seen_at, never from a stored boolean.';
COMMENT ON COLUMN user_sessions.last_seen_at IS
    'Updated by the authenticated heartbeat endpoint. The user/session are taken from the security context, never from the request body.';
COMMENT ON COLUMN user_sessions.ip_address IS
    'Retained for the life of the session row. Cleanup of expired sessions removes it; see the retention section of the business-rules document.';

-- DELIBERATELY NOT AUDIT-TRIGGERED.
-- Every heartbeat updates last_seen_at, so an AFTER UPDATE audit trigger would
-- write one audit_logs row per session per minute per user and drown the audit
-- log in noise. Administrative revocation stays fully attributable through the
-- row's own revoked_at / revoked_by business columns, which is what "audit
-- friendly" requires here. If trigger auditing is ever wanted, audit_trigger_fn
-- must first learn to skip last_seen_at the way it already skips
-- effective_work_code_category_id.
