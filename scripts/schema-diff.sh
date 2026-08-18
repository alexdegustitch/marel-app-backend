#!/usr/bin/env bash
#
# Does production's schema match what this repository says it should be?
#
# Nothing records which of the 110 scripts in src/main/resources/sql have been
# applied to production - they are run by hand. That is tolerable today only
# because ddl-auto=validate makes the application refuse to start when an
# entity has no column. It stops being tolerable the moment Flyway is
# introduced, because baselining is an assertion: "production already has
# everything up to version X". If that assertion is wrong, Flyway records the
# missing script as applied and it never runs again - silently, with no error.
#
# This script checks the assertion before anyone makes it. It builds a
# reference database the way the integration tests do (baseline snapshot, then
# every migration at or after the cutoff, in filename order), takes a canonical
# inventory of both that and production, and diffs them.
#
# Empty diff means production matches the repository and can be baselined.
# A non-empty diff is drift that must be explained before Flyway goes anywhere
# near it.
#
# Usage:
#   PROD_DATABASE_URL='postgresql://user:pass@host:5432/dbname' ./scripts/schema-diff.sh
#
# The URL is read from the environment and never printed or written to disk.
# Read-only: production is only ever queried through pg_catalog.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SQL_DIR="src/main/resources/sql"
BASELINE="src/test/resources/db/baseline-schema.sql"
HARNESS="src/test/java/com/aleksandarparipovic/marel_app/support/AbstractIntegrationTest.java"
INVENTORY="scripts/schema-inventory.sql"

die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

# --- preconditions ----------------------------------------------------------

[ -n "${PROD_DATABASE_URL:-}" ] || die "PROD_DATABASE_URL is not set.
       Usage: PROD_DATABASE_URL='postgresql://user:pass@host/db' $0"

for cmd in docker psql; do
    command -v "$cmd" >/dev/null 2>&1 || die "$cmd not found on PATH"
done
docker info >/dev/null 2>&1 || die "the Docker daemon is not running (needed for the reference database)"

[ -f "$BASELINE" ]  || die "baseline snapshot not found at $BASELINE"
[ -f "$INVENTORY" ] || die "inventory query not found at $INVENTORY"

# The image tag and the cutoff are read out of the test harness rather than
# duplicated here. If the tests move to a new Postgres major or re-baseline,
# this script follows instead of quietly comparing against the wrong thing.
PG_IMAGE="$(grep -oE 'postgres:[0-9]+[a-z-]*' "$HARNESS" | head -1)"
[ -n "$PG_IMAGE" ] || die "could not read the Postgres image tag from $HARNESS"

CUTOFF="$(grep -oE 'BASELINE_CUTOFF = "[^"]+"' "$HARNESS" | sed -E 's/.*"([^"]+)".*/\1/')"
[ -n "$CUTOFF" ] || die "could not read BASELINE_CUTOFF from $HARNESS"

WORK="$(mktemp -d)"
CONTAINER=""
cleanup() {
    [ -n "$CONTAINER" ] && docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "reference image : $PG_IMAGE"
echo "baseline cutoff : $CUTOFF"

# --- build the reference database -------------------------------------------

step "Starting a throwaway $PG_IMAGE"
CONTAINER="$(docker run -d -e POSTGRES_PASSWORD=reference -e POSTGRES_DB=reference \
             -P "$PG_IMAGE")"
PORT="$(docker port "$CONTAINER" 5432/tcp | head -1 | sed 's/.*://')"
REF_URL="postgresql://postgres:reference@127.0.0.1:${PORT}/reference"

printf 'waiting for readiness'
for _ in $(seq 1 60); do
    if docker exec "$CONTAINER" pg_isready -U postgres -d reference >/dev/null 2>&1; then
        printf ' ok\n'; break
    fi
    printf '.'; sleep 1
done
docker exec "$CONTAINER" pg_isready -U postgres -d reference >/dev/null 2>&1 \
    || die "the reference database never became ready"

step "Applying the baseline snapshot"
psql -v ON_ERROR_STOP=1 -q "$REF_URL" -f "$BASELINE" >/dev/null

step "Applying migrations at or after $CUTOFF, in filename order"
count=0
while IFS= read -r file; do
    name="$(basename "$file")"
    [[ "$name" < "$CUTOFF" ]] && continue
    printf '    %s\n' "$name"
    psql -v ON_ERROR_STOP=1 -q "$REF_URL" -f "$file" >/dev/null \
        || die "migration failed: $name
       The repository cannot build its own schema, so there is nothing to
       compare production against. Fix the migration first."
    count=$((count + 1))
done < <(find "$SQL_DIR" -maxdepth 1 -name '*.sql' | sort)
echo "    ($count migrations applied)"

# --- inventory both sides ---------------------------------------------------

step "Taking inventory of the reference database"
psql -At -q "$REF_URL" -f "$INVENTORY" | grep -v 'flyway_schema_history' > "$WORK/reference.txt"

step "Taking inventory of production (read-only)"
psql -At -q "$PROD_DATABASE_URL" -f "$INVENTORY" 2>/dev/null \
    | grep -v 'flyway_schema_history' > "$WORK/production.txt" \
    || die "could not read the production schema - check PROD_DATABASE_URL and network access"

echo "    reference:  $(wc -l < "$WORK/reference.txt" | tr -d ' ') objects"
echo "    production: $(wc -l < "$WORK/production.txt" | tr -d ' ') objects"

# --- compare ----------------------------------------------------------------

step "Comparing"
if diff -u "$WORK/production.txt" "$WORK/reference.txt" > "$WORK/schema.diff"; then
    cat <<'MSG'

    MATCH. Production holds exactly the schema this repository describes.

    The Flyway baseline assertion is safe to make: production can be
    baselined at the latest migration in src/main/resources/sql.
MSG
    rm -rf "$WORK"
    exit 0
fi

added=$(grep -c '^+[^+]' "$WORK/schema.diff" || true)
removed=$(grep -c '^-[^-]' "$WORK/schema.diff" || true)

cat <<MSG

    DRIFT. Production and the repository disagree.

      $removed line(s) present in production but not in the reference
      $added line(s) the repository expects but production lacks

    Lines the repository expects and production lacks are the dangerous
    ones: a migration that was never applied. Baselining now would mark it
    applied forever.

    Full diff (production is '-', repository is '+'):
      $WORK/schema.diff
MSG

echo
echo "    First 40 differing lines:"
grep -E '^[+-][^+-]' "$WORK/schema.diff" | head -40 | sed 's/^/      /'
exit 1
