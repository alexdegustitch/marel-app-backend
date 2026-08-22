#!/usr/bin/env bash
#
# Runs the app locally with secrets loaded from .env.
#
# Spring Boot does not read .env files on its own - there is no dotenv
# starter on the classpath (deliberately: one shell script is less to
# reason about than a new runtime dependency for something ./mvnw can do on
# its own). This sources .env into the shell's environment, then hands off
# to the same Maven target you'd run by hand.
#
# For running from an IDE instead, set the same five variables in the Run
# Configuration's environment (IntelliJ: Run/Debug Configurations > Environment
# variables) - sourcing a shell file only reaches processes launched from
# this shell.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ENV_FILE="${1:-.env}"

if [ ! -f "$ENV_FILE" ]; then
    echo "error: $ENV_FILE not found. Copy .env.example to .env and fill in real values." >&2
    exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

for var in DB_PASSWORD BOOTSTRAP_ADMIN_PASSWORD JWT_SECRET; do
    if [ -z "${!var:-}" ]; then
        echo "error: $var is empty in $ENV_FILE. The app will fail to start without it." >&2
        exit 1
    fi
done
# GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET are allowed to be
# blank - see .env.example for why that's different from unset.

# Mail credentials come as a pair. Half of one is worse than neither: the Brevo
# adapter registers on MAIL_USERNAME alone, then fails authentication on every
# message and burns its five retries before giving up.
if { [ -n "${MAIL_USERNAME:-}" ] && [ -z "${MAIL_PASSWORD:-}" ]; } \
    || { [ -z "${MAIL_USERNAME:-}" ] && [ -n "${MAIL_PASSWORD:-}" ]; }; then
    echo "error: set BOTH MAIL_USERNAME and MAIL_PASSWORD in $ENV_FILE, or neither." >&2
    exit 1
fi

exec ./mvnw spring-boot:run
