# Archived test fixtures

`baseline-schema.sql` and `reference-data.sql` built the pre-Flyway test
schema: a snapshot of everything before `2026-07-21`, plus the audit
configuration rows `reference-data.sql` restored because that snapshot was
schema-only. `AbstractIntegrationTest` applied them, then every migration
script from `2026-07-21` onward, by hand, through psql.

`AbstractIntegrationTest` no longer does that — Flyway does, against
`src/main/resources/db/migration/V1__baseline.sql`, which is the schema and
reference data these two files plus every migration through
`src/main/resources/sql/archive/` produce, dumped as one file (see that
migration's header for exactly how). Kept here for provenance, not used by
any test.
