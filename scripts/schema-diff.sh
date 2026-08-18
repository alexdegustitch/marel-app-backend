#!/usr/bin/env bash
#
# Does a target database's schema match what this repository's Flyway
# migrations say it should be?
#
# Written while adopting Flyway, before this project had any deployed
# database to reconcile against - so it was never actually run against a real
# target; V1__baseline.sql was generated straight from a freshly built
# database instead. It stays, because the question it answers gets asked
# again the moment a real database exists: is a re-baseline (or a Flyway run
# against an already-populated database) safe? Baselining is an assertion -
# "this database already has everything up to version X" - and if that
# assertion is wrong, Flyway records a migration as applied when it was
# never run, silently, with no error.
#
# It builds a reference database by applying every file in
# src/main/resources/db/migration in filename order - the same thing Flyway
# itself would do, done independently through psql so a bug in this script
# cannot agree with a bug in a real Flyway run for the same reason. It takes
# a canonical inventory of both that and the target through pg_catalog, and
# diffs them.
#
# Empty diff means the target matches the repository. A non-empty diff is
# drift that must be explained before Flyway (or a re-baseline) goes near it.
#
# Usage:
#   PROD_DATABASE_URL='postgresql://user:pass@host:5432/dbname' ./scripts/schema-diff.sh
#
# The URL is read from the environment and never printed or written to disk.
# Read-only: the target is only ever queried through pg_catalog.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MIGRATION_DIR="src/main/resources/db/migration"
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

[ -d "$MIGRATION_DIR" ] || die "migration directory not found at $MIGRATION_DIR"
[ -f "$INVENTORY" ]     || die "inventory query not found at $INVENTORY"

# The image tag is read out of the test harness rather than duplicated here.
# If the tests move to a new Postgres major, this script follows instead of
# quietly comparing against the wrong one.
PG_IMAGE="$(grep -oE 'postgres:[0-9]+[a-z-]*' "$HARNESS" | head -1)"
[ -n "$PG_IMAGE" ] || die "could not read the Postgres image tag from $HARNESS"

WORK="$(mktemp -d)"
CONTAINER=""
cleanup() {
    [ -n "$CONTAINER" ] && docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "reference image : $PG_IMAGE"

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

step "Applying every migration in $MIGRATION_DIR, in filename order"
# Filename order matches Flyway's own version order as long as version numbers
# compare the same way as strings - true for our date-based V<YYYY>.<MM>.<DD>.<NN>
# scheme (fixed-width fields), but NOT true in general (V2 sorts after V10).
# If the versioning scheme ever changes, this needs to sort by Flyway's actual
# version comparison instead of the filename.
count=0
while IFS= read -r file; do
    name="$(basename "$file")"
    printf '    %s\n' "$name"
    psql -v ON_ERROR_STOP=1 -q "$REF_URL" -f "$file" >/dev/null \
        || die "migration failed: $name
       The repository cannot build its own schema, so there is nothing to
       compare the target against. Fix the migration first."
    count=$((count + 1))
done < <(find "$MIGRATION_DIR" -maxdepth 1 -name '*.sql' | sort)
[ "$count" -gt 0 ] || die "no migration files found in $MIGRATION_DIR"
echo "    ($count migrations applied)"

# --- inventory both sides ---------------------------------------------------

step "Taking inventory of the reference database"
psql -At -q "$REF_URL" -f "$INVENTORY" > "$WORK/reference.raw" \
    || die "could not query the reference database"
# grep -v exits 1 when nothing is left after filtering - which is a normal
# outcome (an empty or near-empty schema), not a failure. Its exit status is
# ignored on purpose; psql's own exit status above is what is actually checked.
grep -v 'flyway_schema_history' "$WORK/reference.raw" > "$WORK/reference.txt" || true

step "Taking inventory of production (read-only)"
psql -At -q "$PROD_DATABASE_URL" -f "$INVENTORY" > "$WORK/production.raw" 2>"$WORK/production.err" \
    || die "could not read the production schema - check PROD_DATABASE_URL and network access
$(sed 's/^/       /' "$WORK/production.err")"
grep -v 'flyway_schema_history' "$WORK/production.raw" > "$WORK/production.txt" || true

echo "    reference:  $(wc -l < "$WORK/reference.txt" | tr -d ' ') objects"
echo "    production: $(wc -l < "$WORK/production.txt" | tr -d ' ') objects"

# --- compare ----------------------------------------------------------------

step "Comparing"
if diff -u "$WORK/production.txt" "$WORK/reference.txt" > "$WORK/schema.diff"; then
    cat <<'MSG'

    MATCH. The target holds exactly the schema this repository's migrations
    describe.

    A "this database already has everything up to version X" assertion is
    safe to make against it - whether that means running Flyway here for
    the first time, or re-baselining.
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
# grep writes to a file rather than a live pipe on purpose: piping straight
# into `head -40` lets head close the pipe as soon as it has its 40 lines,
# which under `set -o pipefail` can SIGPIPE grep and end the script here with
# whatever exit status that produces instead of the deliberate `exit 1` below.
grep -E '^[+-][^+-]' "$WORK/schema.diff" > "$WORK/differing-lines.txt" || true
head -40 "$WORK/differing-lines.txt" | sed 's/^/      /'
exit 1
