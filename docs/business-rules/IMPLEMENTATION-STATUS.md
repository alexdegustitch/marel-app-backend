# Implementation status and continuation notes

Companion to
[`user-requests-mailing-notifications-and-preferences.md`](user-requests-mailing-notifications-and-preferences.md),
which holds the business rules. **This file records what actually exists in code
right now.** A later agent must be able to continue from the repository alone,
with no access to the conversation that produced this.

Last updated: **2026-07-21**. Nothing here is committed — all changes are in the
working tree.

---

## 1. Summary

| Area | Migration | Entities / repos | Service | Controller | Tests |
|---|:-:|:-:|:-:|:-:|:-:|
| User account status | ✅ | ✅ | ✅ | ✅ (via auth) | ✅ |
| Registration approval | ✅ | ✅ | ✅ | ✅ | ✅ |
| Permission layer | n/a | ✅ | ✅ | n/a | ✅ (indirect) |
| Transactional outbox + worker | ✅ | ✅ | ✅ | n/a | ✅ |
| Manufacturing-time requests | ✅ | ✅ | ✅ | ✅ | ✅ |
| ↳ raised on a production-order line | ✅ `V3` | ✅ | ✅ | ✅ | ✅ |
| ↳ answered by a manufacturing time (N:1) | ✅ `V4` | ✅ | ✅ | ✅ | ✅ |
| Mailing lists | ✅ | ✅ | ✅ | ✅ | ✅ |
| Production-order recipients | ✅ | ✅ | ✅ | ✅ | ✅ |
| Notification events / user notifications | ✅ | ✅ | ✅ | ✅ | ✅ |
| Notification deliveries + worker | ✅ | ✅ | ✅ | n/a | ⚠️ manual only |
| Sessions and presence | ✅ | ✅ | ✅ | ✅ | ⚠️ manual only |
| Preferences / table prefs / saved views | ✅ | ✅ | ✅ | ✅ | ⚠️ manual only |

**All 15 tables are migrated, and every capability has a service and (where
applicable) an API.** The migrations HAVE been applied to the dev database
`marel_app`.

Automated coverage is 30 integration tests over the highest-risk guarantees.
The three ⚠️ rows were verified manually end to end against a running application
but have no automated test yet — they are the first thing to add.

---

## 2. Verification actually performed

Commands were run; these are real results, not expectations.

### Migrations — ✅ verified

A throwaway database `marel_migtest` was created from a `pg_dump` of the live dev
database (schema **and** data — 11 users, 79 legacy link rows). **The dev database
`marel_app` was never modified.**

```bash
createdb marel_migtest
pg_dump -d marel_app --no-owner --no-privileges -f /tmp/marel_dump.sql
psql -q -d marel_migtest -f /tmp/marel_dump.sql
cd src/main/resources/sql
for f in 2026-07-21-*.sql; do psql -v ON_ERROR_STOP=1 -q -d marel_migtest -f "$f"; done
```

Results:
- All 9 scripts applied cleanly in filename order, against real data.
- Applied **twice** with no error — the sequence is idempotent.
- All 15 new tables created; `requests` and `notifications` stubs dropped.
- Backfill: 9 users → `ACTIVE` with `activated_at` set; 2 users →
  `PENDING_APPROVAL`, each given a `PENDING` registration request.

**Filename ordering is load-bearing.** The `-01-` … `-09-` prefixes exist because
alphabetical globbing otherwise runs the audit script first, before its tables
exist. Keep the prefixes.

### Constraints — ✅ 27/27 rejected or accepted as designed

Verified by direct SQL: registration reviewer-state coherence, one-pending-per-user,
request target/type/assignment/processing coherence, `DELETE` type rejection,
per-owner case-insensitive list names, exactly-one-member-source, email
normalization, **CRLF header-injection rejection**, duplicate active member,
recipient source consistency, `SYSTEM`-with-`added_by` rejection, removal-state
pairing, duplicate active recipient per order, duplicate list attachment.

### Account-status trigger — ✅ verified

- Insert `PENDING_APPROVAL` → `is_active=false`, `activated_at` null
- → `ACTIVE` → `is_active=true`, `activated_at` stamped
- Legacy path (`is_active=false` alone) → `SUSPENDED`, **not** `DECLINED`
- → `DECLINED` → `is_active=false`
- **Contradictory write** (`account_status='ACTIVE', is_active=false` in one
  statement) → status wins, `is_active=true`. Contradictory states are impossible.

### Compilation — ✅ verified

```bash
./mvnw -o clean compile     # BUILD SUCCESS
```

### Entity ↔ SQL agreement — ✅ verified

The application was started against `marel_migtest` with `ddl-auto=update`, and
the schema was dumped before and after. **The diff was empty** (apart from
pg_dump's random restrict token): Hibernate made zero schema changes, so every
entity written so far matches its hand-written SQL exactly. Re-run this check
after adding each remaining entity.

### Registration workflow, end to end — ✅ verified against a running app

Exercised over HTTP against `marel_migtest`:

| Check | Result |
|---|---|
| `POST /api/auth/register` creates user + `PENDING` request atomically | ✅ user 17 `PENDING_APPROVAL`, request 7 `PENDING` |
| Outbox event written in the same transaction | ✅ `USER_REGISTRATION_REQUESTED` |
| Pending user login | ✅ 403 `ACCOUNT_NOT_USABLE`, `accountStatus: PENDING_APPROVAL` |
| Admin approve | ✅ request `APPROVED` + reviewer + timestamp; user `ACTIVE`, `activated_at` set |
| Approved user can log in | ✅ 200 |
| **Double approve** | ✅ **409** "Zahtev je već obrađen (APPROVED)" — no silent overwrite |
| Admin decline | ✅ user `DECLINED`, `archived_at` still NULL (a decline does not archive) |
| Declined user login | ✅ 403 `accountStatus: DECLINED` |
| `commercial` role listing all requests | ✅ 403 |
| `commercial` role approving | ✅ 403 |
| No token | ✅ 403 |
| **Status change mid-token-life** (suspend, reuse still-valid token) | ✅ 403 immediately, not at the 15-min expiry |

### Defects found by actually running it

Five, none of which review had caught. They are recorded because each represents a
trap this codebase will set again.

1. **Jackson 2 vs Jackson 3.** Spring Boot 4 auto-configures a **Jackson 3**
   (`tools.jackson`) mapper, while this project's JSONB columns use **Jackson 2**
   (`com.fasterxml`) nodes. Injecting `ObjectMapper` failed at startup, and later a
   Jackson 2 `JsonNode` on a request DTO failed to bind at runtime with
   `HttpMessageConversionException`. **Rule: DTOs expose `Map`/`List`, entities keep
   Jackson 2 nodes, and `common/JsonPayloads` converts between them. Never put a
   `JsonNode` of either generation on a DTO.**
2. **`@Transactional` self-invocation.** Both workers called their own
   transactional method from `@Scheduled`, bypassing the proxy: rows came back
   detached, `FOR UPDATE SKIP LOCKED` released immediately, and every status update
   was silently discarded — events stayed PENDING and were reprocessed forever.
   Fixed by moving the transactional work into separate beans
   (`OutboxBatchProcessor`, `DeliveryBatchProcessor`).
3. **Outbox self-deadlock.** Holding `FOR UPDATE` on an outbox row while fanning
   out in a nested `REQUIRES_NEW` transaction deadlocked: `notification_events` has
   a foreign key to `outbox_events`, so the child insert waited on the parent row
   lock the outer transaction would not release until the nested call returned.
   Observed stuck for 29 minutes. Fixed with the three-phase claim → commit →
   process-per-event design now in `OutboxBatchProcessor`.
4. **DB-generated column not read back.** `User.fullName` is
   `GENERATED ALWAYS AS`, so a freshly persisted user had `fullName == null` for the
   rest of the transaction and leaked a null name into notification payloads. Fixed
   with Hibernate's `@Generated(event = {INSERT, UPDATE})`.
5. **Malformed bodies returned 500.** An invalid enum value fell through to the
   generic handler and reported "Something went wrong". Now a 400 via a
   `HttpMessageNotReadableException` handler.

**The idempotency design proved itself during defect 2:** the event was reprocessed
on every 2-second tick for minutes, and the database still held exactly one
`notification_event` and three `user_notifications`.

### Automated test suite — ✅ 30 tests, 0 failures

```
./mvnw clean verify
```

| Suite | Tests | Covers |
|---|:-:|---|
| `SchemaConstraintsIT` | 3 | check constraints, partial unique indexes, retired stubs |
| `RegistrationApprovalIT` | 6 | atomic registration, approve/decline, terminal status, one-pending-per-user, pending login refused |
| `ManufacturingTimeRequestIT` | 10 | transitions, claim-before-decide, self-processing refused, **completion rollback**, result linkage |
| `RecipientSnapshotIT` | 7 | snapshot independence, cross-list dedup, manual dedup, detach scope, DELIVERED lock |
| `OutboxIdempotencyIT` | 4 | replay creates no duplicate event or notification, permission-based recipients |
| `MarelAppApplicationTests` | 1 | pre-existing smoke test |

Integration tests are named `*IT` and run under **Failsafe in the `verify`
phase**, not under Surefire in `test`. They need Docker; keeping them out of
`mvn test` leaves the fast loop usable without it.

`spring.jpa.hibernate.ddl-auto=validate` is set for the test profile, so any
drift between an entity and its hand-written SQL **fails the build** instead of
being silently patched the way `update` does in dev.

### NOT verified — be honest about this

- **Concurrency is not covered by an automated test.** The double-approve 409 and
  terminal-status assertions are the sequential paths; the `@Version` and
  `FOR UPDATE` races still need multi-threaded tests.
- **Delivery retry/backoff, sessions/presence, and preferences/saved views have no
  automated tests.** All were exercised manually against a running application
  (results below), but that is not repeatable in CI.
- The email path ends at the logging adapter — no real provider was exercised.
- No formatter or static analysis was run; the project configures neither.

---

## 3. Files added

**Migrations** (`src/main/resources/sql/`) — apply in this order; the numeric
prefixes ARE the order:

```
2026-07-21-01-user-account-status.sql
2026-07-21-02-user-registration-requests.sql
2026-07-21-03-manufacturing-time-requests.sql
2026-07-21-04-mailing-lists.sql
2026-07-21-05-production-order-recipients.sql
2026-07-21-06-notifications-and-outbox.sql
2026-07-21-07-user-sessions.sql
2026-07-21-08-user-preferences.sql
2026-07-21-09-audit-new-business-tables.sql
```

**Java** — by package:

| Package | Contents |
|---|---|
| `config/security` | `AppPermission`, `RolePermissions`, `PermissionService` (bean `perm`) |
| `common` | `ConflictException`, `ErrorSanitizer`, `JsonPayloads` |
| `auth` | `AccountNotUsableException` |
| `user` | `UserAccountStatus` |
| `user_registration_request` | status enum, entity, repository, service, controller, 2 DTOs |
| `manufacturing_time_request` | type + status enums, entity, repository, service, controller, 4 DTOs. Since `V3__manufacturing_time_request_line_item.sql` a request may name the production-order line it was raised on — nullable, composite-FK-checked against the product, see business rules §3.1.1 |
| `mailing_list` | visibility enum, entity, repository, service, controller, 6 DTOs |
| `mailing_list_access` | entity + id class, repository |
| `mailing_list_member` | entity, repository |
| `production_order_mailing_list` | entity, repository |
| `production_order_recipient` | source-type enum, entity, repository, service, controller, 2 DTOs |
| `outbox` | 3 enums, entity, repository, publisher, `NotificationFanoutService`, `OutboxBatchProcessor`, `OutboxEventWorker` |
| `notification_event` | entity, repository |
| `user_notification` | entity, repository, service, controller, DTO |
| `notification_delivery` | channel + status enums, entity, repository, `EmailSender`, `LoggingEmailSender`, config, `DeliveryBatchProcessor`, `NotificationDeliveryWorker` |
| `user_session` | entity, repository, service, controller, DTO |
| `user_preferences` | 2 enums, entity, repository, service, controller, 2 DTOs |
| `user_table_preferences` | `TableKey` registry, entity, repository, service, controller |
| `user_saved_view` | entity, repository, service, controller, 2 DTOs |

**Tests** (`src/test/`): `support/AbstractIntegrationTest`, `SchemaConstraintsIT`,
`RegistrationApprovalIT`, `ManufacturingTimeRequestIT`, `RecipientSnapshotIT`,
`OutboxIdempotencyIT`, `application-test.properties`, `db/baseline-schema.sql`,
`db/reference-data.sql`.

**Docs:** `docs/business-rules/` (this file and the business-rules document).

## 4. Files modified

| File | Change | Behaviour that must stay unchanged |
|---|---|---|
| `user/User.java` | Added `accountStatus`, `activatedAt` | `active` still readable; DTO/spec/frontend contract unchanged |
| `auth/AuthService.java` | Login/refresh/Google gates now use `requireUsableAccount`; both register paths set `PENDING_APPROVAL` and open a request | Password is still checked **before** account state, so the response cannot enumerate usernames |
| `auth/CustomUserDetails.java` | Carries `accountStatus`; `isEnabled()` = ACTIVE | Authorities unchanged (`ROLE_<roleName>`) |
| `auth/JwtAuthenticationFilter.java` | Skips authentication for a disabled principal | Unauthenticated requests behave as before |
| `common/GlobalExceptionHandler.java` | Added 403 `ACCOUNT_NOT_USABLE`, 409 conflict, 409 optimistic-lock, 403 access-denied | Existing handlers untouched |
| `bootstrap/AdminInitializer.java` | Bootstrap admin created `ACTIVE` | — |
| `user/UserService.java` | Admin-created users are `ACTIVE` | — |
| `user/UserRepository.java` | Added `findActiveByRoleNames` for permission-based notification recipients | Existing queries untouched |
| `auth/JwtService.java` | Access token carries a `sid` claim (refresh-token family) so the heartbeat resolves the caller's own session | `generateAccessToken(user)` overload kept |
| `auth/refresh/RefreshToken*` | Added non-locking `findByTokenHash` lookups used by logout | Rotation and revocation semantics unchanged |
| `product_manufacturing_time/*` | Added `sourceRequest`, `createForUser`, `applyUpdate`, `findBySourceRequest_Id`; table is now audit-triggered | Existing create/update/delete behaviour unchanged |
| `production_order/ProductionOrder*` | No behaviour change — referenced by the recipient snapshot | — |
| `application.properties` | Added `app.outbox.*`, `app.notifications.delivery.*`, `app.session.*` | Existing keys untouched |
| `pom.xml` | Testcontainers BOM + 3 test-scope deps, Failsafe plugin | No runtime dependency added |

**API change to communicate to the frontend team:** login for a non-ACTIVE account
now returns **403 with `code: "ACCOUNT_NOT_USABLE"` and `accountStatus`**, where it
previously returned 400 `"User is inactive"`. This is required by the
pending-approval screen. The frontend repository was **not** modified.

---

## 5. Remaining work

The capabilities are implemented. What is left is coverage and polish.

1. **Automated tests for the three ⚠️ areas** — delivery retry/backoff and the
   permanently-failed path, session heartbeat/presence/revocation, and
   preferences/table-preferences/saved-views validation. All were verified
   manually; none is repeatable in CI.
2. **Concurrency tests.** Two threads approving the same registration (expect one
   409 from `@Version`), two claiming the same manufacturing-time request (expect
   one to lose the `FOR UPDATE` race), two attaching the same mailing list to one
   order (expect the unique index to hold).
3. **A real `EmailSender` adapter.** The port and the whole delivery pipeline
   exist; only the provider is missing. Adding a bean that implements
   `EmailSender` replaces `LoggingEmailSender` automatically — nothing else
   changes. SMTP credentials must come from the environment, never the repo.
4. **`PRODUCTION_ORDER_COMPLETED` is not yet emitted.** The event type, the
   recipient-snapshot email fan-out and the delivery path are all built and
   tested, but nothing publishes the event: `ProductionOrderService` does not call
   `OutboxEventPublisher` when an order moves to `DELIVERED`. That is a one-line
   addition inside the existing status-change transaction, deliberately left out
   because changing production-order behaviour was outside the approved scope.
5. **Cleanup jobs.** Retention is documented but only session expiry and processed
   outbox rows are realistic candidates; neither job is written.
6. **Frontend integration.** The API change below needs communicating.

### API change to communicate

Login for a non-ACTIVE account now returns **403 with
`code: "ACCOUNT_NOT_USABLE"` and an `accountStatus` field**, where it previously
returned 400 `"User is inactive"`. This is required for the pending-approval
screen. **The frontend repository was not modified.**

New endpoints: `/api/registration-requests`, `/api/manufacturing-time-requests`,
`/api/mailing-lists`, `/api/production-orders/{id}/recipients`,
`/api/notifications`, `/api/sessions`, `/api/user-preferences`,
`/api/user-table-preferences`, `/api/saved-views`.

---

## 6. Known risks

1. **`ddl-auto=update` will mutate whatever database a running app points at.**
   This already happened once during this work: a running instance picked up the
   new entities and auto-created skeleton `user_registration_requests` and
   `outbox_events` tables in `marel_app` without any of their constraints or
   indexes. Those were dropped and the real migrations applied, and the schema is
   now correct — but the hazard is permanent. **Stop the application before adding
   an entity, and apply migrations deliberately.** The test profile uses
   `ddl-auto=validate` so drift fails the build there.

2. **`user_registration_requests` backfill.** Two currently-pending users were
   given synthetic `PENDING` requests dated from their `created_at`. If either was
   actually meant to be *declined*, an administrator must decline them explicitly —
   the old schema could not express the difference.
3. **The `is_active` ↔ `account_status` trigger changes write semantics.** Code
   that sets `is_active` alone now also moves `account_status`. This is intended
   and preserves existing behaviour, but any future code writing both columns in
   one statement will find `account_status` wins.
4. **Legacy audit rows.** `audit_logs` still holds 79 + 6 + 9 rows for the retired
   stubs, now pointing at `*_legacy` names in `audit_tables`. This is intentional
   preservation, not leftover garbage — do not "clean it up".
5. **`app.user_id` in workers.** `AuditUserAspect` only sets the audit actor when a
   security context exists. The outbox and delivery workers run without one, so
   their writes audit as `NULL` user — the same as the existing recalc workers
   (handled by `2026-06-28-audit-trigger-null-safe-user-id.sql`). Expected, not a
   bug.
6. **No email provider.** Deliveries will accumulate as `PENDING` until an
   `EmailSender` adapter is wired. Decide deliberately whether the worker should
   run at all before then.

---

## 7. Commands

```bash
# Apply migrations (order matters — keep the numeric prefixes)
cd src/main/resources/sql
for f in 2026-07-21-*.sql; do psql -v ON_ERROR_STOP=1 -d marel_app -f "$f"; done

# Fast loop: unit tests only, no Docker needed
./mvnw test

# Full verification: adds the *IT integration suite (needs Docker)
./mvnw clean verify

# Rehearse migrations against a copy before touching a real database
createdb marel_rehearsal
pg_dump -d marel_app --no-owner --no-privileges | psql -q -d marel_rehearsal
```

**The migrations HAVE been applied to the dev database `marel_app`** (after
dropping the two empty Hibernate-created skeleton tables described in risk 1). A
`pg_dump` backup was taken first.

### Regenerating the test schema baseline

`src/test/resources/db/baseline-schema.sql` is a snapshot of the full schema, and
`db/reference-data.sql` holds the `audit_tables`/`audit_actions` rows the audit
trigger resolves by name. Regenerate both after any schema change:

```bash
pg_dump -d marel_app --schema-only --no-owner --no-privileges \
  | grep -v '^\\restrict' | grep -v '^\\unrestrict' \
  > src/test/resources/db/baseline-schema.sql

pg_dump -d marel_app --data-only --no-owner --no-privileges \
  --table=audit_actions --table=audit_tables \
  | grep -v '^\\restrict' | grep -v '^\\unrestrict' \
  >> src/test/resources/db/reference-data.sql
```

If you forget, `ddl-auto=validate` in the test profile fails the build — which is
the point.
