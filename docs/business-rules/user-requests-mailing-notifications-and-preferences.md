# User requests, mailing lists, notifications, sessions and preferences

Authoritative business rules for: user registration approval, manufacturing-time
requests, user-managed mailing lists, production-order recipients, persistent
notifications, transactional event processing, sessions and online presence, user
preferences, table preferences, saved views, and audit/activity tracking.

This document is the durable record. Do not re-derive these rules from a
conversation; correct **this file** when a rule changes.

**Implementation status is tracked in
[`IMPLEMENTATION-STATUS.md`](IMPLEMENTATION-STATUS.md).** Everything described
here is implemented — schema, services and API — and the migrations have been
applied to the dev database. That file records what is covered by automated tests
versus verified manually, the remaining work, and the known risks. Read it before
changing anything here.

---

## 1. Repository facts that shaped every decision

These were verified against the real repository and database, not assumed. They
override generic Spring Boot expectations.

| Fact | Consequence |
|---|---|
| **No migration framework.** `spring.jpa.hibernate.ddl-auto=update` plus hand-applied dated SQL in `src/main/resources/sql/` | New schema ships as dated SQL scripts. Entities must match the SQL exactly, or Hibernate will silently "fix" the difference on startup. |
| **No permission model.** Authorization is 4 roles (`admin`, `supervisor`, `commercial`, `developer`) checked with `hasRole` | Permissions are a *naming layer* over roles (`AppPermission` + `RolePermissions`), not a new table. |
| **No email infrastructure.** No `spring-boot-starter-mail`, no SMTP config | `notification_deliveries` and the worker exist; the provider is an `EmailSender` port. Nothing actually sends mail until an adapter is wired. |
| **Test infrastructure added by this work.** Testcontainers, test scope only | Integration tests are named `*IT` and run under Failsafe in `mvn verify`, against a real PostgreSQL 18. The test profile uses `ddl-auto=validate`, so entity/SQL drift fails the build. |
| **Audit is trigger-based**, `audit_trigger_fn` + `AuditUserAspect` setting `app.user_id` | New tables reuse it. No second audit framework. `audit_trigger_fn` reads `NEW.id`, so **an audited table must have a single-column `id`**. |
| **Enums are `VARCHAR` + `CHECK`**, JPA `EnumType.STRING` | Same everywhere below. Ordinal persistence is never used. |
| **Timestamps are `TIMESTAMPTZ`** | Same everywhere below. No mixed strategy. |
| **Case-insensitive email is a functional unique index** over `lower(...)` (`uq_users_email_address_ci`) | Same approach used for members and recipients. `CITEXT` deliberately not introduced. |
| **Soft delete** is `archived_at` / `is_active` depending on the table | New tables use `archived_at` only. |
| **Two Jackson generations coexist.** Spring Boot 4's HTTP converter is Jackson 3 (`tools.jackson`); the JSONB entity columns use Jackson 2 (`com.fasterxml`) | **DTOs expose `Map`/`List`; entities keep Jackson 2 nodes; `common/JsonPayloads` converts.** A `JsonNode` on a DTO fails at runtime. |
| **`@Transactional` needs a bean boundary** | A `@Scheduled` method must never call its own transactional method — the proxy is bypassed and every write is silently discarded. Workers delegate to a separate processor bean. |

### 1.1 Retired stub tables

Three tables existed in the database with **no Java entity, repository, service,
controller, or frontend caller**, holding only demo rows seeded in one batch on
2026-01-08 15:29:

| Stub | Rows | Disposition |
|---|---|---|
| `requests` (`id, user_id, is_active, …` — no status, type or description) | 6 | **Dropped.** Retired in `audit_tables` as `requests_legacy`. |
| `manufacturing_time_requests` (link `request_id → production_order_id`) | 79 | **Dropped**, name reused for the real workflow table. Retired as `manufacturing_time_requests_legacy`. |
| `notifications` (flat per-user, no read state) | 9 | **Dropped**, replaced by `notification_events` + `user_notifications`. Retired as `notifications_legacy`. |

Existing `audit_logs` rows for the stubs are **preserved**; renaming the
`audit_tables` entries keeps them pointing at the retired tables they actually
describe. The drops are shape-guarded (they only fire when the stub's
distinguishing column is present), so re-running a script can never drop a real
populated table.

---

## 2. User registration approval

### 2.1 Account statuses (`users.account_status`)

| Status | Meaning | Can authenticate |
|---|---|---|
| `PENDING_APPROVAL` | Registered, waiting for an administrator | No |
| `ACTIVE` | Approved and usable | **Yes** |
| `DECLINED` | An administrator refused the registration | No |
| `SUSPENDED` | Was active, administratively disabled. Reversible | No |
| `ARCHIVED` | Retired, kept for historical references | No |

`account_status` is **authoritative**. `users.is_active` still exists (existing
specifications, DTOs and the frontend read it) but is **derived** by the trigger
`trg_00_users_account_status_sync` and can never contradict the status. There is
no `is_approved`/`is_declined` boolean pair.

The sync is bidirectional so that pre-existing code touching only `is_active`
still lands somewhere sensible:

- `account_status` changed → `is_active := (status = 'ACTIVE')`. **Status wins**
  even if `is_active` was set contradictorily in the same statement.
- only `is_active` changed → `true` ⇒ `ACTIVE`; `false` from `ACTIVE` ⇒
  `SUSPENDED`. **Never `DECLINED`** — declining is a registration-review outcome
  and must go through the workflow.
- reaching `ACTIVE` stamps `activated_at` once; it is never cleared.

**Pre-existing archive behaviour is unchanged.** `trg_01_users_clear_archive_on_reactivate`
and `trg_02_users_archived_at` still manage `archived_at` from `is_active`
transitions, so suspending an account still stamps `archived_at`. `account_status`
is the workflow state; `archived_at` remains the older "not currently usable"
marker. A `DECLINED` account is **not** archived (its `is_active` never changed
from `true`, so the archive trigger does not fire).

Backfill from the pre-migration data:
`archived_at` set ⇒ `ARCHIVED`; `is_active = false` ⇒ `PENDING_APPROVAL`;
otherwise `ACTIVE`. A previously *declined* account cannot be recovered because
the concept did not exist — such rows land in `PENDING_APPROVAL`, which is the
safe direction (still no access, still reviewable).

### 2.2 Registration-request statuses and transitions

`user_registration_requests.status`: `PENDING`, `APPROVED`, `DECLINED`, `CANCELLED`.

```
PENDING -> APPROVED
PENDING -> DECLINED
PENDING -> CANCELLED
```

Everything else is refused with HTTP 409. **There is no reopen path**; `APPROVED`,
`DECLINED` and `CANCELLED` are terminal. A refused person registers again and gets
a new request. Enforced in `UserRegistrationRequestStatus.canTransitionTo` and by
the entity having **no public status setter** — only `approve`/`decline`/`cancel`.

### 2.3 Rules

1. Registration creates **the user and one `PENDING` request in one transaction**
   (`UserRegistrationRequestService.openFor` is `Propagation.MANDATORY`, so it
   cannot accidentally run in its own transaction). A user without a request would
   be invisible to reviewers and stuck forever.
2. A user has **at most one open request**, enforced by the partial unique index
   `uq_user_registration_requests_one_pending`, not only by an application check.
3. Requests are **never physically deleted** after review.
4. While `PENDING`, `reviewed_at` and `reviewed_by` are `NULL`; when `APPROVED` or
   `DECLINED`, both are required. Enforced by
   `chk_user_registration_requests_review_state`.
5. `review_note` is optional for approval and is where a decline reason goes.
   Blank is stored as `NULL`.
6. Approval atomically sets request `APPROVED` + reviewer + timestamp, and user
   `ACTIVE` (+ `activated_at` via trigger).
7. Decline atomically sets request `DECLINED` + reviewer + timestamp, and user
   `DECLINED`. It does **not** archive the account.
8. Cancellation sets `CANCELLED` + `reviewed_at`; the account stays
   `PENDING_APPROVAL`, because withdrawing is not a refusal and must not read like
   one in history.
9. **Email verification is not approval.** A Google-verified identity still lands
   in `PENDING_APPROVAL` with an open request.
10. A `PENDING_APPROVAL` user cannot reach protected endpoints. Enforced at two
    layers: `AuthService.requireUsableAccount` refuses to issue tokens, and
    `JwtAuthenticationFilter` refuses to authenticate a principal whose
    `isEnabled()` is false — so a status change takes effect immediately rather
    than at the 15-minute token expiry.

### 2.4 Login response for a non-usable account

A pending account is **not** reported as an authentication failure. `AuthService`
verifies the password **first** (so the response cannot be used to enumerate
usernames), then throws `AccountNotUsableException`, rendered as:

```
HTTP 403
{ "error": "...", "code": "ACCOUNT_NOT_USABLE", "accountStatus": "PENDING_APPROVAL" }
```

The client branches on `accountStatus` to show a pending-approval screen.

### 2.5 Authorization

Reviewing requires `USER_REGISTRATION_APPROVE`; listing everyone's requires
`USER_REGISTRATION_READ_ALL`. Both are granted to `admin` and `developer` only.
Cancellation additionally allows the **applicant themselves** — checked
horizontally in the controller, so knowing an id is not enough to cancel someone
else's registration.

### 2.6 Concurrency

`UserRegistrationRequest` carries `@Version`. Two administrators approving the
same pending row: exactly one commits, the other gets
`OptimisticLockingFailureException` → HTTP 409 with a reload-and-retry message.
The partial unique index guarantees there was only ever one pending row to race
over.

---

## 3. Manufacturing-time requests

### 3.1 Types (`request_type`)

`CREATE`, `UPDATE`, `RECALCULATE`, `DEACTIVATE`.

**Deviation from the incoming specification:** the type is `DEACTIVATE`, not
`DELETE`. `ProductManufacturingTimeService.delete()` is a soft delete
(`is_active = false`); the domain has no physical delete, so offering `DELETE`
would name an operation the system cannot perform.

**Deviation:** the retired stub linked a request to *many production orders*.
The real manufacturing-time domain (`product_manufacturing_times`) hangs off a
**product**, so the table references `product_id`. The stub's many-to-many
production-order association carried no business meaning and is not reproduced.

`CREATE` must have **no** `target_manufacturing_time_id`; every other type must
have one (`chk_manufacturing_time_requests_target_required`).

### 3.1.1 The production-order line a request was raised on

`manufacturing_time_requests.production_order_line_item_id` is **nullable** and
names the line the request came from. Added 2026-08-22 in
`V3__manufacturing_time_request_line_item.sql`.

The line is the **occasion**, never the subject:

- `product_id` stays `NOT NULL` and keeps its meaning. Manufacturing times hang
  off a product, so a request with only a line item would leave completion with
  nothing to write.
- `NULL` means "raised on its own", not "about nothing". A request raised from
  the manufacturing-time screen is exactly as valid as one raised from an order.
- When a line IS given, the line's product and the request's product must be the
  same one. This is a **composite foreign key**
  (`fk_manufacturing_time_requests_line_item`) over
  `(production_order_line_item_id, product_id)` referencing
  `production_order_line_items (id, product_id)`, so the two cannot drift apart
  for any writer, including SQL run by hand. `MATCH SIMPLE` is deliberate: with
  the line NULL the key is not checked at all.
- The column is **not updatable**. The occasion is a fact about how the request
  came to exist.
- A request may not be raised on an **inactive** line (409), and a line may have
  **at most one open request** (409) — `PENDING` or `IN_REVIEW`. Both are service
  rules, not constraints; see the caveat below for why the second one is not an
  index.

**Line identity is not stable across an order edit.** `ProductionOrderService`
retires an order's lines (`is_active = false`) and writes NEW rows rather than
updating them. The consequences were accepted deliberately:

- The reference stays valid and historically truthful — it keeps pointing at the
  line as it was when somebody asked.
- The "one open request per line" rule therefore cannot be a unique index: after
  an order edit the same need is a different line id. It is a service check whose
  job is to stop the obvious double-ask, not a concurrency guarantee. The rule
  that IS race-proof — one open request per targeted manufacturing-time record —
  is unchanged.

### 3.2 Statuses and transitions

`PENDING`, `IN_REVIEW`, `COMPLETED`, `DECLINED`, `CANCELLED`.

```
PENDING   -> IN_REVIEW      (assign / claim)
PENDING   -> CANCELLED      (requester withdraws)
IN_REVIEW -> COMPLETED
IN_REVIEW -> DECLINED
IN_REVIEW -> CANCELLED
```

**`PENDING -> COMPLETED` and `PENDING -> DECLINED` are NOT allowed.** A request
must be owned before it is decided, so that `assigned_to` always identifies who
took responsibility. A processor who wants to decide immediately claims first;
the API offers a claim-and-process sequence, never a blind status write.

**Completing an unowned request claims it on the way** (`claimIfUnowned`, added
2026-08-23). The status machine is unchanged — the request still goes
`PENDING -> IN_REVIEW -> COMPLETED`, both steps in one transaction — and
`assigned_to` still ends up naming whoever did the work. What changed is only
that it takes one call instead of two: completing is done from the
manufacturing-time screen, where the processor picks a request and hands it a
result in one motion.

Two limits keep this from being a takeover:
- a request somebody **else** owns is untouched, and then refused. Taking work
  away from a colleague stays a deliberate reassignment.
- **declining does not auto-claim.** Turning somebody's request down is not
  something to fall into from a queue, so it still requires claiming first.

`COMPLETED`, `DECLINED` and `CANCELLED` are terminal — a finished request can
never be processed again. There is **no generic status-update endpoint**.

Database-enforced state coherence:
- `PENDING`/`IN_REVIEW` ⇒ no `processed_by`, `processed_at` or `cancelled_at`
- `COMPLETED`/`DECLINED` ⇒ `processed_by` **and** `processed_at` required
- `CANCELLED` ⇒ `cancelled_at` required, no processor
- `IN_REVIEW` ⇒ `assigned_to` required; `PENDING` ⇒ `assigned_to` must be `NULL`

### 3.3 Actor fields

| Field | Meaning |
|---|---|
| `created_by` | Who submitted the request |
| `assigned_to` | Who currently owns it (set when it becomes `IN_REVIEW`) |
| `processed_by` | Who completed or declined it |
| `reviewed_by` (registration) | The administrator who decided the registration |

These are **current business state**, not audit metadata. The audit log records
*how the row got here*; these columns record *who owns the outcome now*. They are
not duplicates of each other. No ambiguous `changed_by` is used anywhere.

### 3.4 Relationship to the manufacturing-time record

There are **two links, answering two different questions.** They cannot
contradict each other because one records authorship and the other records the
answer.

| Column | Question | Cardinality | Written when |
|---|---|---|---|
| `manufacturing_time_requests.result_manufacturing_time_id` | Which record **answers** this request? | many requests → one record | the request is completed, whether the record was newly produced or an existing one attached |
| `product_manufacturing_times.source_request_id` | Which request last **wrote** this record? | one record ↔ one request | only when a request actually creates or changes the record |

**A manufacturing time may answer many requests.** Two people can ask for the
same product's time and one record settles both. That is why
`result_manufacturing_time_id` lives on the request: a foreign key belongs on the
side that has many. `source_request_id` could not carry this — it is one column
on the record, so a second request answered by the same record would overwrite
the first and leave a `COMPLETED` request with no result.
`uq_pmt_source_request_id` does not prevent that; it enforces the opposite
direction.

**Attaching is not authorship.** Completing a request with an existing record
fills `result_manufacturing_time_id` and deliberately leaves `source_request_id`
alone. Overwriting the stamp would take authorship away from the request that
produced the record — and, through that column, that request's own history.

`chk_manufacturing_time_requests_result_state` makes "COMPLETED" and "has a
result" the same fact: a finished request always has one, an unfinished or
refused one never does. The result's product is checked against the request's
product by the composite key `fk_manufacturing_time_requests_result`, the same
idiom as §3.1.1.

Added 2026-08-23 in `V4__manufacturing_time_request_result.sql`, which backfills
the new column from `source_request_id` — every request completed before it
produced its own record, so the two agreed by construction.

#### 3.4.1 The authorship link

`product_manufacturing_times.source_request_id` → `manufacturing_time_requests.id`,
with a **partial unique index** (`uq_pmt_source_request_id`).

**Cardinality: one request produces at most one manufacturing-time record.** A
`CREATE` inserts one row; `UPDATE`/`RECALCULATE`/`DEACTIVATE` mutate the targeted
row and re-stamp `source_request_id`. The column therefore means *"the request
that most recently produced the current state of this record"*. The full chain of
earlier requests lives in `audit_logs` — `product_manufacturing_times` was
**previously unaudited** (`audit_tables` held a stale entry named
`manufacturing_product_times` matching no real table) and is now registered and
trigger-audited.

Operation-level rows (`product_manufacturing_time_operations`) hang off the parent
record and are not separately linked.

The model can answer: which request produced the current result
(`source_request_id`), which record was targeted
(`target_manufacturing_time_id`), and whether a request was declined without
producing anything (`status = DECLINED` and no row references it).

### 3.5 Rules

1. Completing the request and creating/updating its result happen in **one
   transaction**. A failure while producing the result must leave the request
   un-completed — never a `COMPLETED` request with no result.
2. The requester **cannot process their own request**. Submitting and deciding are
   separate responsibilities and no current role rule grants an exception.
3. Only an open request may be assigned; reassignment of an already-owned request
   requires the process permission.
4. A requester may cancel only while the request is still `PENDING` or
   `IN_REVIEW`.
5. Optimistic locking (`@Version`) prevents two processors both completing or
   declining the same request.
6. A request may name the production-order line it was raised on; see §3.1.1.
   Doing so changes nothing about the workflow — the same statuses, the same
   processing rules, the same result.
7. A `CREATE` request may be completed by **attaching a manufacturing time that
   already exists** (`existingManufacturingTimeId` on the decision payload)
   instead of by producing a new one. The record must be active and belong to the
   request's product; it may already answer other requests. The other request
   types refuse an attachment — the record they act on is the target they already
   name.
8. An illegal completion is refused **before** any result is produced
   (`requireCompletable`), so a refused completion never writes a
   manufacturing-time row that the rollback then has to take back.
9. `existingManufacturingTimeId` may travel **together with**
   `manufacturingTimeUpdate`: the named record is rewritten and the request
   settled in one transaction. This is the "I reworked the numbers on an existing
   record" case. Reworking **is** authorship, so `source_request_id` moves to the
   request that did it — unlike plain attaching, which leaves it alone.

### 3.6 Authorization

Creating a request: any authenticated user — including `commercial`, which is
who raises requests from a production order. Nothing about the line item changes
this: raising a request on a line requires no permission the plain request does
not already require. Claiming, completing, declining, and
listing all requests: `MANUFACTURING_TIME_REQUEST_PROCESS` /
`MANUFACTURING_TIME_REQUEST_READ_ALL` (`admin`, `supervisor`, `developer`). A user
without the read-all permission sees only their own requests.

---

## 4. Mailing lists

### 4.1 Ownership and visibility

A mailing list is owned by its creator (`owner_user_id`).

| Visibility | Who may see and use it |
|---|---|
| `PRIVATE` | The owner only |
| `SHARED` | The owner + users explicitly granted access in `mailing_list_access` |
| `GLOBAL` | Any user holding `MAILING_LIST_GLOBAL_MANAGE` |

`SHARED` needs its own grant table because **no generic resource-permission
mechanism exists** in this codebase.

### 4.2 Rules

1. Active name uniqueness is scoped **per owner**, case-insensitively, and only
   while `archived_at IS NULL` (`uq_mailing_lists_owner_name_active`). Two owners
   may each have a "Kupci"; an archived list does not block reusing its name.
2. An **archived list cannot be attached to new production orders**, but archiving
   **never** removes existing production-order relationships — that is exactly why
   `production_order_mailing_lists` uses `ON DELETE RESTRICT` on the list.
3. Access to `PRIVATE` and `SHARED` lists is enforced on the backend. A user must
   not reach another user's private list by guessing an id, and must not attach
   someone else's private list to a production order.
4. Editing a `GLOBAL` list requires `MAILING_LIST_GLOBAL_MANAGE`.
5. Granting shared access requires ownership or that same permission.

### 4.3 Members

Exactly one member source, enforced by
`chk_mailing_list_members_exactly_one_source`:

```
(user_id IS NOT NULL AND external_email IS NULL)
OR (user_id IS NULL AND external_email IS NOT NULL)
```

- An **application user** member is `user_id`. If that user later changes their
  email address, the membership keeps following the user — the address is
  deliberately *not* snapshotted here.
- An **external person** is `external_email`, stored already lower-cased. The
  database rejects non-normalized addresses, whitespace, and CR/LF (SMTP header
  injection) — not only bean validation.
- Duplicate prevention is case-insensitive: one active row per `user_id`, one per
  `lower(external_email)`, per list.

**Known limit:** an `external_email` equal to some application user's current
address cannot be excluded by a constraint, because a user member's effective
address lives in `users.email_address`. `MailingListMemberService` performs that
cross-source check in the application, and the recipient snapshot deduplicates by
normalized address anyway — so a slipped duplicate can still never produce two
emails for one person.

Member removal is an **archive** (`archived_at`), never a delete, so it stays
auditable.

A member whose account becomes `SUSPENDED` or `ARCHIVED` **remains visible
historically but is excluded from new snapshots**. Membership changes **never**
retroactively modify an existing production-order recipient snapshot.

---

## 5. Production-order mailing lists and recipients

Two tables, two responsibilities that must never be collapsed:

- `production_order_mailing_lists` — **which lists were selected** (intent)
- `production_order_recipients` — **who the order actually mails** (snapshot)

### 5.1 Snapshot rules

1. Attaching a list copies its **currently active members** into
   `production_order_recipients`. This is a snapshot.
2. A later change to the mailing list **never** alters an existing order's
   recipients.
3. A manually typed address is written directly with `source_type = MANUAL`.
4. A backend-determined recipient is written with `source_type = SYSTEM` and
   `added_by = NULL` (there is no human author).
5. `recipient_email` is **always** populated, even when `user_id` is set, so
   history shows where the mail actually went if the user later changes address.
6. `source_mailing_list_id` is populated **only** when `source_type =
   MAILING_LIST` (`chk_po_recipients_source_list_consistency`).
7. **Deduplication:** one active recipient per normalized address per order,
   enforced by `uq_po_recipients_order_email_active`. Someone appearing in three
   selected lists, or in a list *and* as a manual entry, receives exactly one
   email. Enforced in the database so two concurrent attaches cannot both insert.
8. When an address arrives from several lists, the kept row's
   `source_mailing_list_id` names the **first** contributing list. **No additional
   recipient-source link table is created** — no current audit requirement asks
   "which other lists also contained this address", `production_order_mailing_lists`
   already records every selected list, and `audit_logs` records each insert.
9. Removing a recipient sets `removed_at` **and** `removed_by` together
   (`chk_po_recipients_removal_state`). Never a delete.
10. Attaching a list and creating its snapshot happen in **one transaction**. If
    snapshot creation fails, the link must not remain.
11. **Email for a production order is sent from `production_order_recipients`,
    never by re-resolving mailing-list membership at send time.**

### 5.2 Mailing-list detachment rule (chosen, not guessed)

Matched to the existing production-order lifecycle (`CREATED` → `DELIVERED`):

- **While the order is `CREATED`:** detaching a list also archives the active
  recipients whose **only** source was that list (`source_type = 'MAILING_LIST'`
  and `source_mailing_list_id` = the detached list). `MANUAL` and `SYSTEM`
  recipients are never touched, nor is a row attributed to another list.
- **Once the order is `DELIVERED`:** the snapshot is **locked** — no attach, no
  detach, no manual add, no removal. `DELIVERED` is the terminal state at which the
  order has been communicated, so its recipient history must stop moving.

Enforced in `ProductionOrderRecipientService`, not by a trigger: this repository
keeps workflow rules in the service layer and uses triggers only for timestamps,
archiving and auditing.

### 5.3 Authorization

A caller must be allowed to modify the production order itself
(`PRODUCTION_ORDER_RECIPIENT_MANAGE`). **Manual recipient entry must not become a
bypass around production-order authorization** — the same check guards both.

---

## 6. Notifications

### 6.1 Transient feedback vs. persistent notifications

| Transient (frontend only, never persisted) | Persistent (these tables) |
|---|---|
| "The record was created successfully" | A registration request was submitted |
| "The changes were saved" | A manufacturing-time request was assigned / completed / declined |
| "An error occurred" | A production order was completed |

The frontend's `notification.service.tsx` is Mantine toasts. Those must **never**
reach the database. The test: if a user could log out, come back, and still need
to see it, it is persistent.

### 6.2 Model

| Table | Responsibility |
|---|---|
| `notification_events` | **What** happened — one row per business event |
| `user_notifications` | **Who** must see it in-app, plus read/dismiss state |
| `notification_deliveries` | **How** it goes out per channel, plus retry state |

`entity_type` + `entity_id` identify the primary business entity and are
authoritative. `payload` (JSONB) is supplementary display metadata only and is
never the sole representation of a relationship.

Event types: `USER_REGISTRATION_REQUESTED`, `USER_REGISTRATION_APPROVED`,
`USER_REGISTRATION_DECLINED`, `MANUFACTURING_TIME_REQUEST_CREATED`,
`MANUFACTURING_TIME_REQUEST_ASSIGNED`, `MANUFACTURING_TIME_REQUEST_COMPLETED`,
`MANUFACTURING_TIME_REQUEST_DECLINED`, `PRODUCTION_ORDER_COMPLETED`.

### 6.3 User-notification rules

1. One event may reach many users.
2. A user has **at most one** `user_notifications` row per event
   (`uq_user_notifications_event_user`) — this is what makes outbox fan-out safe to
   replay.
3. `read_at IS NULL` means unread.
4. `dismissed_at` hides it from the active list **without deleting** it.
5. Marking as read is **idempotent** (a second call changes nothing).
6. A user may read, dismiss or restore **only their own** rows.
7. Unread counts hit the partial index `idx_user_notifications_unread`, so the
   query touches a tiny index regardless of history size.
8. A notification's text must not expose data the recipient is not permitted to
   see, and following an entity reference through the API **still performs the
   normal authorization check** on that entity.

### 6.4 Recipient resolution

Resolved from **permission and business relationship**, never a hard-coded role
name at the call site (`PermissionService.roleNamesWith`):

| Event | In-app recipients |
|---|---|
| `USER_REGISTRATION_REQUESTED` | Users holding `USER_REGISTRATION_APPROVE` |
| `USER_REGISTRATION_APPROVED` / `_DECLINED` | The applicant |
| `MANUFACTURING_TIME_REQUEST_CREATED` | Users holding `MANUFACTURING_TIME_REQUEST_PROCESS` |
| `MANUFACTURING_TIME_REQUEST_ASSIGNED` | The assignee |
| `MANUFACTURING_TIME_REQUEST_COMPLETED` / `_DECLINED` | The requester (`created_by`) |
| `PRODUCTION_ORDER_COMPLETED` | The order's responsible user, plus every active `production_order_recipients` row as an EMAIL delivery |

A user's `user_preferences.in_app_notifications_enabled` /
`email_notifications_enabled` are consulted before creating the corresponding
delivery — which is precisely why those two are typed columns, not JSON keys.

---

## 7. Delivery and retry

Channels: `IN_APP`, `EMAIL`.
Statuses: `PENDING`, `PROCESSING`, `SENT`, `FAILED`, `CANCELLED`.

```
PENDING    -> PROCESSING
PROCESSING -> SENT
PROCESSING -> FAILED
FAILED     -> PROCESSING   (retry, once next_attempt_at is due)
PENDING    -> CANCELLED
FAILED     -> CANCELLED
```

1. External recipients have `recipient_user_id = NULL`. `recipient_email` holds
   the snapshot address used for the delivery.
2. Delivery is **idempotent**: one email per address per event, one in-app per user
   per event (`uq_notification_deliveries_email`,
   `uq_notification_deliveries_in_app`). Two workers replaying the same event
   cannot produce a duplicate send.
3. Two workers never process the same row — claims use `FOR UPDATE SKIP LOCKED`.
4. `attempt_count` is tracked; retry delay and maximum attempts are **configurable**
   (`app.notifications.*`, see the status document).
5. After the maximum, the row stays `FAILED` and stops being claimed, so it stays
   **visible for operational review** rather than disappearing.
6. `last_error` is **sanitized and truncated to 1000 chars**. It must never contain
   passwords, tokens, personal data, or full provider payloads.
7. A successful delivery sets `sent_at` (`chk_notification_deliveries_sent_at`
   makes `SENT` without `sent_at` impossible).
8. Delivery rows are **never physically deleted** in normal operation.

**The email provider is a port with no adapter yet.** `EmailSender` is called
outside any database transaction — the worker loads its batch, commits the claim,
then sends. A long provider call must never hold a transaction open.

---

## 8. Transactional outbox

Statuses: `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`.

```
PENDING    -> PROCESSING
PROCESSING -> PROCESSED
PROCESSING -> FAILED
FAILED     -> PROCESSING   (retry)
```

1. **The business state change and the outbox insert commit in the same
   transaction.** `OutboxEventPublisher.publish` is `Propagation.MANDATORY` — it
   *cannot* start its own transaction. If it ever did, an event could be committed
   for a change that later rolled back, and users would be told about something
   that never happened.
2. The worker processes events **outside** the original business transaction.
3. Processing is **idempotent**: `notification_events.outbox_event_id` is UNIQUE,
   so a retried outbox row can never create a second event. The unique indexes on
   `user_notifications` and `notification_deliveries` make the fan-out replay-safe
   too.
4. Application restart loses nothing — the queue is a table.
5. Multiple instances are safe via `FOR UPDATE SKIP LOCKED`, the same pattern
   `RecalcQueueService` already uses. **No broker is introduced** — Kafka or
   RabbitMQ would be disproportionate at this scale and would add infrastructure
   the project does not run.
6. **Claiming and processing are separate transactions**, in three phases: claim
   and mark `PROCESSING` → **commit** → process each event in its own transaction.
   This is not a style preference. Holding `FOR UPDATE` on an outbox row while
   fanning out in a nested transaction **deadlocks**: `notification_events` has a
   foreign key to `outbox_events`, so the child insert waits on the parent row
   lock that the outer transaction will not release until the nested call returns.
   Never reintroduce a nested `REQUIRES_NEW` inside the claim.
7. Rows stranded in `PROCESSING` by a crashed instance are reclaimed after
   `app.outbox.stuck-timeout-seconds`. Reprocessing them is safe because fan-out is
   idempotent.
8. An event is **not** marked `PROCESSED` until every required durable record
   exists — fan-out and the `PROCESSED` flag commit in the same transaction.
9. Actual email sending is a **second stage** driven by `notification_deliveries`,
   so the outbox stage never blocks on a provider.
10. `last_error` is sanitized (`common/ErrorSanitizer`). Permanently failed events
    stay visible.

---

## 9. Sessions and online presence

**An active account and an online user are different things.** `ACTIVE` means
allowed to use the application. Online means at least one live session reported
activity recently. There is deliberately **no `users.is_online`** — a crashed
client would strand it as a permanent lie.

`user_sessions` keys on `family_id`, shared with `refresh_tokens.family_id`.
Refresh tokens rotate on every refresh, so a `refresh_tokens` row is **not** a
stable session identity — but the family is: created once at login, carried across
every rotation.

**No `refresh_token_hash` column exists here on purpose.** `refresh_tokens` already
owns the hashes; duplicating secret-derived material into a second table buys
nothing. Raw refresh tokens are **never** stored anywhere — `RefreshTokenService`
SHA-256 hashes before persisting.

### Rules

1. A successful login creates one session; a user may have many (one per device).
2. The client sends a lightweight heartbeat every **30–60 s**; the backend updates
   `last_seen_at`. The interval and the online threshold are **configuration**
   (`app.session.*`), not constants scattered across the code.
3. A user is **online** when at least one session is not revoked, not expired, and
   has `last_seen_at` within the threshold (initially ~2 minutes, configurable).
4. Expired and revoked sessions never count as online.
5. The heartbeat derives user and session **from the authentication context**. The
   client cannot supply a user id — that would let anyone forge anyone's presence.
6. A heartbeat updates; it never creates a session.
7. Logout invalidates the session (`logout_at` + revocation).
8. Administrative revocation requires `USER_SESSION_REVOKE` and always records
   `revoked_by` (`chk_user_sessions_revocation`).
9. The API never exposes token hashes or unnecessary session internals.

**`user_sessions` is deliberately NOT trigger-audited**: every heartbeat is an
UPDATE, so auditing it would write one row per user per minute and drown the audit
log. Revocation stays fully attributable through `revoked_at` / `revoked_by`. If
trigger auditing is ever wanted, `audit_trigger_fn` must first learn to skip
`last_seen_at` the way it already skips `effective_work_code_category_id`.

**IP and user-agent retention:** kept for the life of the session row and removed
when the expired-session cleanup deletes it.

---

## 10. Preferences, table preferences and saved views

Three separate responsibilities. **Do not merge them.**

| Table | Responsibility |
|---|---|
| `user_preferences` | Global, user-level appearance and behaviour |
| `user_table_preferences` | One dense table's column/sort/width layout |
| `user_saved_views` | Named, reusable filter + display configurations |

### 10.1 `user_preferences`

Keyed by `user_id` (no surrogate id), so a second row per user is **structurally
impossible**. Created **lazily on first read**
(`UserPreferencesService.getOrCreateForUser`) — no backfill row for every existing
user, and no coupling to user creation.

Typed columns for anything validated, queried, or read by backend behaviour.
`ui_settings` JSONB for visual-only extras, constrained to a JSON **object** and
16 KB so it cannot become free storage. `theme` ∈ {`SYSTEM`,`LIGHT`,`DARK`};
`ui_density` ∈ {`COMPACT`,`COMFORTABLE`,`SPACIOUS`}; `rows_per_page` ∈ [5, 500].

`email_notifications_enabled` and `in_app_notifications_enabled` are typed columns
specifically because the notification fan-out reads them.

A user may read and update **only their own**; administrative access needs
`USER_PREFERENCES_ADMIN`. Arbitrary client fields are never persisted unvalidated.

### 10.2 `user_table_preferences`

At most one row per `(user_id, table_key)`
(`uq_user_table_preferences_user_table`). `table_key` is validated against an
application-side registry and **is never interpolated into SQL as an identifier**.
`settings` is a JSON object capped at 32 KB. Unsupported columns are **ignored**
(not rejected) so a stale client cannot lock a user out of their own table.

These preferences are **purely presentational**: they never change authorization or
backend filtering, and **hiding a column never causes restricted data to be
returned** — the backend decides what a user may see, independent of layout.

### 10.3 `user_saved_views`

1. A user may have many views per `view_key`.
2. **At most one active default per `(user_id, view_key)`**, enforced by the partial
   unique index `uq_user_saved_views_one_default` — so the "unset old, set new"
   transaction cannot race into two defaults.
3. Setting a new default unsets the previous one **in the same transaction**.
4. Archived views (`archived_at`) do not appear in the active list, and an archived
   view can never be the default (`chk_user_saved_views_default_not_archived`).
5. `filters`, `sorting`, `columns` are validated field-by-field against the same
   registry the list endpoints use.
6. **Never store executable SQL. Never concatenate saved filter values into SQL.**
   Values are bound as parameters.
7. Saved views **never widen** what a user is allowed to see.
8. A user manages only their own. **Shared saved views are deliberately not
   implemented** — no current requirement asks for them.

---

## 11. Audit

The existing mechanism is reused unchanged: register the table in `audit_tables`,
attach `audit_trigger_fn`, let `AuditUserAspect` supply the actor through
`set_config('app.user_id', …)`. **No second audit framework.**

**Audited** (added by these migrations): `user_registration_requests`,
`manufacturing_time_requests`, `product_manufacturing_times` (**previously
unaudited** — a gap this work closes), `mailing_lists`, `mailing_list_members`,
`production_order_mailing_lists`, `production_order_recipients`,
`notification_events`.

**Deliberately not audited, with reasons:**

| Table | Why not |
|---|---|
| `user_notifications` | Every read/dismiss would write an audit row; `read_at`/`dismissed_at` already record it |
| `notification_deliveries` | Worker retry churn, several writes per delivery |
| `outbox_events` | Same, and the payload is already an event record |
| `user_sessions` | One write per heartbeat per user per minute |
| `user_preferences`, `user_table_preferences`, `user_saved_views` | Personal display settings with no business or payroll consequence — auditing adds noise, not accountability |
| `mailing_list_access` | Composite PK, no `id` column, so `audit_trigger_fn` (which reads `NEW.id`) cannot handle it |

**Business actor vs. audit actor.** `reviewed_by`, `processed_by`, `added_by`,
`removed_by`, `revoked_by` are part of current domain state and are **not**
duplicates of the audit log. The columns say who owns the state now; the audit log
says how it got there. Never place secrets or bulk payloads in audit JSON.

---

## 12. Activity tracking

**Decision: `user_activity_events` is NOT implemented — classified as *optional
future capability*.**

No analytics or usage-event tracking exists in the repository, and no current
requirement or approved planned feature depends on it. Creating the table now
would be exactly the speculative modelling this schema avoids. The distinction
matters: preferences, audit and activity tracking are three different things, and
merging them later would be worse than adding the table when a real requirement
appears.

If it is ever added, the rules are: never track every click; only events with a
clear operational purpose; no sensitive field values in metadata; a defined
retention period; and logging failures must never fail a business transaction.

---

## 13. Retention

| Data | Rule |
|---|---|
| Registration requests | **Never deleted.** Permanent business history |
| Manufacturing-time requests | **Never deleted.** Permanent business history |
| Mailing lists / members | Archived (`archived_at`), never deleted |
| Production-order recipients | **Never deleted.** Removal is `removed_at`/`removed_by` |
| Notification events | Retained with the entity they describe |
| User notifications | Retained; `dismissed_at` hides without deleting |
| Delivery records | **Never deleted in normal operation.** Failed rows stay inspectable |
| Outbox events | `PROCESSED` rows are cleanup candidates (mirrors the existing `app.recalc.done-retention-days=7` pattern). `FAILED` rows are **never** auto-deleted |
| User sessions | Expired sessions are a cleanup candidate; removing them also removes the retained IP and user-agent |
| Audit history | **Never auto-deleted.** No approved retention policy exists |

Cleanup jobs must be configurable and tested. **Audit and business-request history
are never automatically deleted.**

---

## 14. Delete behaviour summary

Historical business entities use `ON DELETE RESTRICT`; only rows that are
meaningless without their parent cascade.

| Relationship | Behaviour | Why |
|---|---|---|
| `user_registration_requests.user_id` → `users` | RESTRICT | Deleting a user must not silently delete reviewed registration history |
| `manufacturing_time_requests.product_id` → `products` | RESTRICT | Deleting a product must not silently delete request history |
| `product_manufacturing_times.source_request_id` | RESTRICT | The producing request must survive its result |
| `production_order_mailing_lists.mailing_list_id` → `mailing_lists` | RESTRICT | Archiving a list must not remove production-order history |
| `production_order_mailing_lists.production_order_id` | CASCADE | Follows the existing production-order child policy |
| `production_order_recipients.production_order_id` | CASCADE | Same |
| `mailing_list_access` / `mailing_list_members` → `mailing_lists` | CASCADE | A grant or membership has no meaning without its list |
| `notification_deliveries.notification_event_id` | RESTRICT | Deleting an event must not orphan delivery records |
| `user_notifications.notification_event_id` | RESTRICT | Same |
| `user_preferences` / `user_table_preferences` / `user_saved_views` → `users` | CASCADE | Personal settings have no meaning without the user, and carry no business history |
| every actor column (`reviewed_by`, `processed_by`, `added_by`, …) | RESTRICT | An actor reference is history and must not vanish |

---

## 15. Permissions

No permission table exists. `AppPermission` is a **naming layer** over the existing
roles; `RolePermissions` is the single place that maps role → capability. When a
real permission model arrives, only `RolePermissions` changes.

| Permission | admin | developer | supervisor | commercial |
|---|:-:|:-:|:-:|:-:|
| `USER_REGISTRATION_APPROVE` | ✅ | ✅ | | |
| `USER_REGISTRATION_READ_ALL` | ✅ | ✅ | | |
| `MANUFACTURING_TIME_REQUEST_PROCESS` | ✅ | ✅ | ✅ | |
| `MANUFACTURING_TIME_REQUEST_READ_ALL` | ✅ | ✅ | ✅ | |
| `MAILING_LIST_GLOBAL_MANAGE` | ✅ | ✅ | | |
| `PRODUCTION_ORDER_RECIPIENT_MANAGE` | ✅ | ✅ | ✅ | ✅ |
| `USER_SESSION_REVOKE` | ✅ | ✅ | | |
| `USER_PREFERENCES_ADMIN` | ✅ | ✅ | | |

Usage: `@PreAuthorize("@perm.has('USER_REGISTRATION_APPROVE')")`.
`@EnableMethodSecurity` is already enabled in `SecurityConfig`.

---

## 16. API rules

1. Never expose JPA entities; always request/response DTOs.
2. The client may **never** set: `created_by`, `reviewed_by`, `processed_by`,
   `added_by`, `actor_user_id`, its own `user_id`, `created_at`, `updated_at`,
   `processed_at`, `reviewed_at`, delivery status, outbox status, or retry counts.
   All derive from the authentication context, backend logic, server time, or the
   current transaction.
3. List endpoints paginate. **Never return an unbounded** request, notification,
   session or history collection. Page size is clamped server-side.
4. Filtering uses validated fields only. Arbitrary property names are never
   reflected into queries.
5. Workflow entities expose **no generic CRUD and no generic status setter** —
   only named operations that encode a legal transition.

---

## 17. Non-negotiable rules

1. `account_status` is authoritative; `is_active` is derived and must never be
   used to express a workflow decision.
2. An **ACTIVE account** and an **ONLINE user** are different concepts. Presence is
   derived from sessions, never a stored boolean.
3. Production-order email uses the **recipient snapshot**, never live mailing-list
   resolution.
4. Mailing-list edits **never** rewrite an existing snapshot.
5. Business change + outbox insert commit **in one transaction**; publishing is
   `MANDATORY`.
6. Completing a request and producing its result commit **in one transaction**.
7. Raw refresh tokens are **never** stored.
8. `last_error` is **always** sanitized.
9. Terminal statuses are terminal. No reopen without an explicit, documented
   workflow.
10. Preferences, audit and activity tracking are three different things. Never
    merge them.
11. Never build a second notification model, a second audit framework, or a second
    request concept.

---

## 18. Deviations from the incoming specification

| # | Specification said | Repository required | Resolution |
|---|---|---|---|
| 1 | Request type `DELETE` | `ProductManufacturingTimeService.delete()` is a soft delete | Type is `DEACTIVATE` |
| 2 | Request → `product_id` | Retired stub linked request → many production orders | Followed the spec (`product_id`); the stub's link had no business meaning and was dropped |
| 3 | Permission `USER_REGISTRATION_APPROVE` | No permission model exists, only 4 roles | Permissions are a naming layer over roles; no new table, authorization model unchanged |
| 4 | `PENDING -> COMPLETED` / `-> DECLINED` "determine whether valid" | Nothing in the repo supports deciding an unowned request | **Not allowed.** A request must be claimed before it is decided |
| 5 | Email delivery with a provider | No mail infrastructure, no dependency | Delivery table + worker + `EmailSender` port; no adapter, no new dependency |
| 6 | `user_activity_events` | No analytics exists, no requirement | **Not implemented.** Classified optional future capability |
| 7 | Shared saved views | No requirement | **Not implemented** |
| 8 | `archived_at` only for intentionally archived entities | Pre-existing `trg_02_users_archived_at` stamps it on any deactivation | Existing behaviour preserved; `account_status` carries the workflow state and the overlap is documented (§2.1) |
| 9 | `user_sessions.refresh_token_hash` | `refresh_tokens` already stores hashes | Column omitted; sessions key on `family_id` |
| 10 | Composite PK for `production_order_mailing_lists` | `audit_trigger_fn` reads `NEW.id` | Surrogate `id` + UNIQUE constraint — same guarantee, auditable |
| 11 | Audit "preference changes where appropriate" | Trigger auditing would create heartbeat/read-state noise | Preferences, sessions, deliveries, outbox and user-notifications not trigger-audited; reasons in §11 |
