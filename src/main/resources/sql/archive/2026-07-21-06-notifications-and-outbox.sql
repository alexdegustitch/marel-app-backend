-- =============================================================================
-- Persistent business notifications, delivery tracking and transactional outbox
-- =============================================================================
-- The stub table `notifications` is dropped. It had no JPA entity, repository,
-- service, controller or frontend caller (the frontend's "notifications" are
-- Mantine toasts — transient UI feedback, never persisted), and its only rows
-- were 9 identical demo records seeded on 2026-01-08. It was a flat per-user
-- table with no read state and no event identity, so one business event fanned
-- out to N users meant N unrelated rows with duplicated text.
--
-- The replacement separates three responsibilities that the stub conflated:
--
--   notification_events    WHAT happened (one row per business event)
--   user_notifications     WHO must see it in-app, and their read/dismiss state
--   notification_deliveries HOW it goes out per channel, with retry state
--
-- Existing audit_logs rows for the stub are preserved; its audit_tables entry is
-- renamed to notifications_legacy so those rows keep describing the retired
-- table.
--
-- Persistent notifications are business events that survive logout ("a
-- registration request is waiting", "your request was declined"). Transient
-- confirmations ("saved successfully") are frontend-only and must never reach
-- these tables.
-- =============================================================================

DROP TABLE IF EXISTS notifications;

UPDATE audit_tables SET table_name = 'notifications_legacy'
WHERE table_name = 'notifications';


-- =============================================================================
-- Transactional outbox
-- =============================================================================
-- The business state change and its outbox row are written in ONE transaction.
-- A worker then turns pending rows into notification events, user notifications
-- and delivery rows, outside that transaction. No broker is introduced: the
-- application already polls PostgreSQL queues with FOR UPDATE SKIP LOCKED
-- (recalc_queue), and this reuses that proven pattern at the same scale.
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type      VARCHAR(60)  NOT NULL,
    aggregate_type  VARCHAR(60)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Sanitized, truncated message only. Never a raw provider payload, token or
    -- personal data — see OutboxEventWorker.sanitizeError.
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,

    CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),

    CONSTRAINT chk_outbox_events_attempt_count
        CHECK (attempt_count >= 0),

    -- A row is processed exactly when it says it is.
    CONSTRAINT chk_outbox_events_processed_at
        CHECK (
            (status = 'PROCESSED' AND processed_at IS NOT NULL)
            OR (status <> 'PROCESSED' AND processed_at IS NULL)
        )
);

-- The worker claim query: due rows, oldest first, PENDING or retryable FAILED.
CREATE INDEX IF NOT EXISTS idx_outbox_events_claimable
    ON outbox_events (next_attempt_at, id)
    WHERE status IN ('PENDING', 'FAILED');

-- Operational review of permanently failed events.
CREATE INDEX IF NOT EXISTS idx_outbox_events_status_created
    ON outbox_events (status, created_at);

-- "what events did this production order / request emit"
CREATE INDEX IF NOT EXISTS idx_outbox_events_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

COMMENT ON TABLE outbox_events IS
    'Transactional outbox. Written in the same transaction as the business change; drained by OutboxEventWorker with FOR UPDATE SKIP LOCKED.';


-- =============================================================================
-- Notification events
-- =============================================================================
CREATE TABLE IF NOT EXISTS notification_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The outbox row that produced this event. UNIQUE, so replaying a retried
    -- outbox row can never create a second event — this is the backbone of
    -- outbox idempotency.
    outbox_event_id BIGINT,
    type            VARCHAR(60)  NOT NULL,
    actor_user_id   BIGINT,
    entity_type     VARCHAR(60)  NOT NULL,
    entity_id       BIGINT       NOT NULL,
    title           VARCHAR(200) NOT NULL,
    message         VARCHAR(2000) NOT NULL,
    -- Supplementary display metadata only. Never the sole representation of a
    -- business relationship — entity_type/entity_id carry that.
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_notification_events_outbox_event_id
        FOREIGN KEY (outbox_event_id) REFERENCES outbox_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_events_actor_user_id
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT chk_notification_events_title
        CHECK (length(trim(title)) > 0),
    CONSTRAINT chk_notification_events_message
        CHECK (length(trim(message)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_events_outbox_event_id
    ON notification_events (outbox_event_id)
    WHERE outbox_event_id IS NOT NULL;

-- "every notification raised about this request / order", newest first.
CREATE INDEX IF NOT EXISTS idx_notification_events_entity
    ON notification_events (entity_type, entity_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_events_created_at
    ON notification_events (created_at DESC);

COMMENT ON TABLE notification_events IS
    'One row per persistent business event. Fanned out to users via user_notifications and to channels via notification_deliveries.';


-- =============================================================================
-- Per-user in-app notification state
-- =============================================================================
CREATE TABLE IF NOT EXISTS user_notifications (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_event_id BIGINT      NOT NULL,
    user_id               BIGINT      NOT NULL,
    read_at               TIMESTAMPTZ,
    dismissed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_user_notifications_notification_event_id
        FOREIGN KEY (notification_event_id) REFERENCES notification_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_notifications_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
);

-- One event reaches a user at most once, however many times processing is
-- retried. This is what makes outbox fan-out safe to replay.
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_notifications_event_user
    ON user_notifications (notification_event_id, user_id);

-- The unread badge. Partial index over unread-and-not-dismissed rows only, so
-- the count query touches a tiny index regardless of history size.
CREATE INDEX IF NOT EXISTS idx_user_notifications_unread
    ON user_notifications (user_id)
    WHERE read_at IS NULL AND dismissed_at IS NULL;

-- The notification centre list: a user's active notifications, newest first.
CREATE INDEX IF NOT EXISTS idx_user_notifications_user_active
    ON user_notifications (user_id, created_at DESC)
    WHERE dismissed_at IS NULL;

COMMENT ON TABLE user_notifications IS
    'A user''s copy of a notification event. read_at IS NULL means unread; dismissed_at hides it from the active list without deleting it.';


-- =============================================================================
-- Channel delivery tracking
-- =============================================================================
CREATE TABLE IF NOT EXISTS notification_deliveries (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_event_id BIGINT       NOT NULL,
    channel               VARCHAR(20)  NOT NULL,
    -- NULL for an external recipient who has no application account.
    recipient_user_id     BIGINT,
    recipient_email       VARCHAR(320),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at               TIMESTAMPTZ,
    -- Sanitized and truncated. Must never contain passwords, tokens, personal
    -- data or a full provider payload — see NotificationDeliveryWorker.
    last_error            VARCHAR(1000),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,

    CONSTRAINT fk_notification_deliveries_notification_event_id
        FOREIGN KEY (notification_event_id) REFERENCES notification_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_deliveries_recipient_user_id
        FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT chk_notification_deliveries_channel
        CHECK (channel IN ('IN_APP', 'EMAIL')),

    CONSTRAINT chk_notification_deliveries_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED')),

    CONSTRAINT chk_notification_deliveries_attempt_count
        CHECK (attempt_count >= 0),

    -- An EMAIL delivery is meaningless without an address; an IN_APP delivery is
    -- meaningless without a user.
    CONSTRAINT chk_notification_deliveries_target
        CHECK (
            (channel = 'EMAIL' AND recipient_email IS NOT NULL)
            OR (channel = 'IN_APP' AND recipient_user_id IS NOT NULL)
        ),

    CONSTRAINT chk_notification_deliveries_email
        CHECK (
            recipient_email IS NULL
            OR (
                recipient_email = lower(trim(recipient_email))
                AND recipient_email LIKE '%_@_%._%'
                AND recipient_email !~ '[[:space:]]'
            )
        ),

    CONSTRAINT chk_notification_deliveries_sent_at
        CHECK (
            (status = 'SENT' AND sent_at IS NOT NULL)
            OR (status <> 'SENT' AND sent_at IS NULL)
        )
);

-- One email per address per event, and one in-app delivery per user per event,
-- no matter how often processing is retried. Two workers replaying the same
-- event cannot produce a duplicate send.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_deliveries_email
    ON notification_deliveries (notification_event_id, lower(recipient_email))
    WHERE channel = 'EMAIL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_deliveries_in_app
    ON notification_deliveries (notification_event_id, recipient_user_id)
    WHERE channel = 'IN_APP';

-- The delivery worker claim query: due rows only.
CREATE INDEX IF NOT EXISTS idx_notification_deliveries_claimable
    ON notification_deliveries (next_attempt_at, id)
    WHERE status IN ('PENDING', 'FAILED');

-- Operational review of permanently failed deliveries.
CREATE INDEX IF NOT EXISTS idx_notification_deliveries_status_created
    ON notification_deliveries (status, created_at);

DROP TRIGGER IF EXISTS trg_03_notification_deliveries_updated_at ON notification_deliveries;
CREATE TRIGGER trg_03_notification_deliveries_updated_at
    BEFORE UPDATE ON notification_deliveries
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE notification_deliveries IS
    'Per-channel delivery attempt for a notification event, with retry state. Rows are never deleted in normal operation.';
