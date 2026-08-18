-- Google-provisioned accounts have no local password; login happens entirely via
-- Google OAuth for those users.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
