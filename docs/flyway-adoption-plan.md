# Adopting Flyway

Status: **proposed, not started.** Nothing here has been executed.

## Why

Migrations are 110 hand-written `.sql` files applied by running `psql -f`. Nothing
records which ones went into which database. The only thing standing between that
and a silent production drift is `spring.jpa.hibernate.ddl-auto=validate`, which
refuses to start the application when an entity maps to a column that is not there.

That guard is real but narrow. It catches a missing column behind a mapped field.
It does not catch a missing index, a missing check constraint, a missing trigger,
or a data-transform migration that was never run — none of which Hibernate
validates, and all of which change behaviour.

## Why Flyway and not Liquibase

Liquibase wants a changelog in XML/YAML/JSON. Applying it here means either
rewriting 110 SQL scripts into changesets or wrapping each in a `<sqlFile>`
include. The first is a large, error-prone rewrite of scripts that are already
correct; the second is an index over the files that adds a format without adding
information.

The abstraction would also not survive contact with these particular scripts.
They use dollar-quoted `DO $$ ... $$` blocks, `pg_constraint` lookups, trigger
registration and audit-table wiring — PostgreSQL-specific work that Liquibase's
database-agnostic changeset types cannot express, so it would end up as raw SQL
inside a changeset wrapper anyway. The portability that justifies Liquibase is
not being bought.

Flyway reads plain `.sql` files in version order, which is what this repository
already has. The change is mostly a rename.

## The repository is already most of the way there

This problem was found and solved by hand in July 2026. The evidence:

| | count | state |
|---|---|---|
| before `2026-07-21` | 37 | no sequence number; 7 dates hold several files with no defined order between them |
| from `2026-07-21` on | 73 | `-NN-` sequence numbers, replayed by the integration suite |

There is a baseline snapshot (`src/test/resources/db/baseline-schema.sql`, 241 KB)
and a cutoff constant (`AbstractIntegrationTest.BASELINE_CUTOFF`). That is Flyway's
baseline concept, written out longhand. Adopting Flyway formalises a structure that
exists rather than introducing a new one.

One property to keep in mind: the baseline is a snapshot taken *after* the
`2026-07-21` migrations, and the suite then re-applies them on top. That is
deliberate — it proves every post-cutoff script is safely re-runnable, on every
test run. Flyway runs each script exactly once, so this property is not something
Flyway preserves; it is something Flyway makes unnecessary.

## The precondition

**Flyway must not be introduced until `master` actually tracks production.**

Baselining is an assertion: *production already contains everything up to version
X*. If it is wrong — if some script never reached production — Flyway writes the
missing version into `flyway_schema_history` as applied and it will never run.
No error, no log line. The failure surfaces later as behaviour nobody can explain.

That assertion cannot currently be made. `master` is 73 commits behind local work,
and the applied set in production is unrecorded. So the order is:

1. Get `master` to match what is deployed, applying any outstanding scripts by hand.
2. Run `./scripts/schema-diff.sh` and get an empty diff. This is what turns the
   baseline from a hope into a checked fact.
3. Only then do anything below.

`scripts/schema-diff.sh` exists for step 2. It builds a reference database the way
the test suite does, takes a canonical catalogue inventory of it and of production,
and diffs them. It is read-only against production.

## The plan

Two shapes are possible. They differ in what happens to the 73 post-cutoff scripts.

### Option A — fresh baseline (recommended)

Snapshot the current production schema as `V1__baseline.sql`. Move all 110 existing
scripts to `src/main/resources/sql/archive/`, where they stay readable as history but
are outside Flyway's `locations`. Production baselines at `V1`; a fresh database gets
`V1` and then every future migration.

This is the standard adoption path and leaves the least machinery behind. What it
gives up is the suite's continuous re-verification of the 73 scripts — a check whose
future value is small, since those scripts have already run everywhere they will
ever run.

### Option B — preserve the replay

Keep `baseline-schema.sql` as `V1__baseline.sql` and rename the 73 post-cutoff
scripts to `V2026.07.21.01__…` and so on. Production baselines at the newest version
so none of them re-run there; a fresh database applies `V1` and then all 73, exactly
as the suite does today.

Closer to current behaviour, but it keeps the idempotency requirement on every script
forever — which is a constraint Flyway is designed to remove. Recommended only if the
re-runnability check is considered worth the cost.

### Either way

- Naming becomes `V<version>__<description>.sql`, with dots in the version:
  `2026-09-25-01-a-norm-is-a-dated-version…sql` → `V2026.09.25.01__a_norm_is_a_dated_version….sql`.
  Dates keep sorting chronologically, so the existing ordering convention survives.
- `flyway.baselineOnMigrate=true` and an explicit `baselineVersion` for the existing
  production database. Both come out once the baseline row exists.
- `AbstractIntegrationTest.loadSchema()` drops the psql replay and lets Flyway migrate
  the Testcontainers database. Tests and production then build schemas the same way
  for the first time — today they share the scripts but not the mechanism.
- `ddl-auto=validate` stays. It is a second, independent check and costs nothing.

## Known constraints

- **`PayrollValueBackfillIT` replays one script by filename**
  (`2026-08-01-03-employee-payroll-value-backfill.sql`, five call sites via
  `runMigrationScript`). It is testing a data transform by feeding it known rows,
  which is worth keeping. The script must stay readable at a stable path; under
  Option A that means the archive directory, and the helper's base path changes.
- **The 37 pre-cutoff scripts cannot be verified by the suite.** They were checked
  against a clone of the dev database by hand and folded into the baseline. Under
  either option they remain history, never executed again.
- **`reference-data.sql` is not a migration** — it seeds `audit_tables` and action
  rows that audited inserts need. It stays a test fixture and must not become a
  versioned migration.
- **Adding Flyway creates the `flyway_schema_history` table in production.** Small
  and additive, but it is a schema change and needs sign-off before it is applied.

## Effort

The renaming and configuration is a short piece of work — under a day. The
precondition is not: reconciling `master` with production and resolving whatever
drift `schema-diff.sh` reports is the real cost, and it is unknown until that
script has been run once.
