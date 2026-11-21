-- =============================================================================
-- A profile can be joined to a Google account
-- =============================================================================
-- WHAT CHANGES
--   users gains three nullable columns:
--
--       google_subject    varchar(64)
--       google_email      varchar(255)
--       google_linked_at  timestamptz
--
--   plus two PARTIAL unique indexes over them. Nothing else: no existing row is
--   touched, no column changes type, and no constraint is added to data that is
--   already there — so this takes a brief ACCESS EXCLUSIVE lock to write the
--   catalogue entries and no table rewrite.
--
-- WHY
--   "Continue with Google" resolved an identity by comparing the Google-verified
--   address with users.email_address and nothing else. That made the feature
--   available to exactly one group by accident — the people whose ACCOUNT address
--   happens to be the Gmail one — and unavailable, with no way to ask, to
--   everybody whose account is on a company address. There was nowhere to record
--   "this profile belongs to that Google account", so the question could not even
--   be answered.
--
--   These three columns are that record: WHICH Google account (subject), WHICH
--   address it signs in with (shown back to its owner on the profile page), and
--   WHEN the person joined the two.
--
-- WHY A SUBJECT AND NOT ONLY AN ADDRESS
--   google_subject is Google's `sub` — the one identifier Google documents as
--   stable for the life of the account. A Gmail address can be changed by its
--   owner, and matching on the address alone would silently break the link the
--   day somebody does, leaving them locked out of a sign-in route that worked
--   yesterday. The address is kept beside it because it is what a person
--   recognises: "povezano sa pera@gmail.com" is readable, a 21-digit subject is
--   not.
--
-- WHAT IT IS NOT
--   · Not a credential. Nothing here is secret and nothing here is sufficient to
--     sign in; the subject is an account number Google hands to this application
--     for this application. It is therefore left in the audit trail (users is
--     audited by trg_audit_logs_users), unlike password_hash, which V10 removed:
--     joining and severing an account IS a decision somebody makes, and an audit
--     trail is for decisions.
--   · Not a second password. An account joined to Google still confirms every
--     signed action (archiving, approving, freezing a payroll) with its LOCAL
--     password, exactly as before.
--   · Not a replacement for the address match. The callback resolves
--     google_subject first, then google_email, and ONLY then falls back to the
--     existing email_address comparison — so every person for whom Google
--     sign-in works today keeps signing in unchanged, with nothing to migrate.
--   · No backfill, deliberately. A link is a statement its owner makes; deriving
--     one from matching addresses would be the application asserting on somebody
--     else's behalf that a Gmail account is theirs, which is exactly the claim
--     only they can make. Every existing row therefore stays NULL.
--
-- THE TWO UNIQUE INDEXES
--   A Google account may be joined to AT MOST ONE profile. Without that, two
--   profiles could claim one Google identity and the callback would have to
--   choose between them — which is the one thing a sign-in must never do.
--   Both are PARTIAL (WHERE ... IS NOT NULL): nearly every row is NULL and NULL
--   rows cost nothing to index, while a plain unique index would also be fine on
--   NULLs but would carry every account for no reason.
--   The address index is on lower(google_email), matching uq_users_email_address_ci
--   — addresses are compared case-insensitively everywhere in this schema.
--
-- ROLLBACK
--   DROP INDEX IF EXISTS uq_users_google_email_ci;
--   DROP INDEX IF EXISTS uq_users_google_subject;
--   ALTER TABLE users
--       DROP COLUMN google_linked_at,
--       DROP COLUMN google_email,
--       DROP COLUMN google_subject;
--
--   Safe at any time. It discards the links people have made — after which
--   Google sign-in falls back to the address match it used before this
--   migration, so nobody is locked out, but anyone on a non-Gmail account
--   address loses the route and has to join again.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN google_subject   varchar(64),
    ADD COLUMN google_email     varchar(255),
    ADD COLUMN google_linked_at timestamptz;

COMMENT ON COLUMN users.google_subject IS
    'Google''s stable `sub` for the joined account; NULL when none is joined. Not a credential.';

COMMENT ON COLUMN users.google_email IS
    'The address the joined Google account signs in with, shown back to its owner. NULL when none is joined.';

COMMENT ON COLUMN users.google_linked_at IS
    'When the owner joined this profile to that Google account; NULL when none is joined.';

-- One Google account, at most one profile. The subject is the authoritative
-- half of that rule because it is the half Google promises not to change.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_google_subject
    ON users (google_subject)
    WHERE google_subject IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_google_email_ci
    ON users (lower(google_email))
    WHERE google_email IS NOT NULL;
