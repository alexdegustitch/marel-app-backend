# Compensation schemes and category localization

Business rules for employee-specific work-category calculation and for the
database-backed translation of category names.

Companion to
[`IMPLEMENTATION-STATUS.md`](IMPLEMENTATION-STATUS.md), which records what is
actually built.

---

## 1. Why compensation schemes exist

Some employees are paid under a policy that differs from the standard one: their
work-code category selection is restricted, and their base coefficient is fixed
regardless of which shift they worked.

Before this feature that distinction had nowhere to live except
`employees.is_foreigner`. That is a **personnel** attribute, and using it as a
payroll rule conflated four separate things. They are now separate and none is
derived from another:

| Concept | Where it lives | What it means |
|---|---|---|
| Nationality / foreign-worker status | `employees.is_foreigner` | Who the person is. **Nothing in the calculation path reads it.** |
| Document language | `employees.preferred_locale` | What language their payslip is in. |
| Payroll policy | `employee_compensation_scheme_history` | How their work is priced. |
| Application language | `user_preferences.language` | What language a logged-in *user* sees the app in. |

`is_foreigner` was used exactly once, by the `2026-07-27-02` migration, to seed
the initial scheme periods. It keeps its original meaning and is never consulted
again.

> **"Foreign employee therefore English" is not a rule this system implements.**
> A foreign employee may read Serbian and a domestic one may want English. The
> language is chosen explicitly.

---

## 2. The two seeded schemes

| Code | Name | `allow_unmapped_categories` |
|---|---|:-:|
| `STANDARD` | Standardni obračun | `true` |
| `FOREIGN_FIXED_COEFFICIENT` | Fiksni koeficijent | `false` |

`allow_unmapped_categories` is the whole behavioural difference:

- **`true`** — a source category with no explicit rule is **allowed**, resolves
  to **itself**, and uses the **normal coefficient logic**
  (`work_code_categories.norm_multiplier`). This is precisely how the system
  behaved before compensation schemes existed, which is why `STANDARD`
  deliberately has **no rules at all**. Seeding identity rules for it would add
  rows that can only drift out of sync.
- **`false`** — a source category with no explicit rule is **unavailable**: it is
  not offered by the API and is **rejected** if submitted directly.

Schemes are resolved in code by `code`, never by a hard-coded id — see
`CompensationSchemeCodes`. Ids differ between environments.

---

## 3. Date-effective scheme history

An employee is attached to a scheme by a **period**, never by a column:

```
employee_compensation_scheme_history
  employee_id, compensation_scheme_id, valid_from, valid_until
```

- `valid_from` **inclusive**, `valid_until` **inclusive**, `NULL` = open-ended.
- Resolution is **always by WORK DATE** — never `now()`, never the payroll run
  date.
- Overlaps are prevented by the `ex_ecsh_no_overlap` GiST exclusion constraint,
  not by a check-then-insert that two concurrent transactions could both pass.

### Transition semantics

```
work BEFORE the transition date  -> the old scheme
work ON and AFTER it             -> the new scheme
```

Changing a scheme **closes the open period** (`valid_until = newFrom - 1`) and
**inserts a new one**. It never edits an existing row's scheme, because that
would silently change what already-recorded work was worth.

### Failure cases

These are reported as clear business errors, never papered over:

| Condition | Result |
|---|---|
| No scheme covers the work date | `409` — "nema definisan način obračuna" |
| More than one scheme covers it | `409` — "preklapajuće periode obračuna" |
| The scheme is inactive or archived | `409` — "nije aktivan" |

**There is deliberately no silent fallback to `STANDARD`.** A missing scheme is a
payroll misconfiguration, and hiding it behind a plausible-looking number is
worse than failing.

New employees get an opening `STANDARD` period from their start date
(`CompensationSchemeInitializer`), never inferred from `is_foreigner`.

---

## 4. The three category concepts

**They must not be merged.** All three coexist on a work log:

| Concept | Column on `work_logs` | Meaning |
|---|---|---|
| **Source** | `work_code_category_id` | What the employee actually worked. Entered by the user, **never overwritten**. |
| **Scheme-effective** | `scheme_effective_work_code_category_id` | What the employee-specific **base calculation** uses. `NULL` = same as source. |
| **Derived / contextual** | `effective_work_code_category_id` | The reversible night/weekend bonus remap from `work_code_category_mappings`, recomputed every recalc. |

> The effective category **does not erase the source category.**

### `work_code_category_scheme_rules` vs `work_code_category_mappings`

Two tables, two questions, **both run**:

```
work_code_category_scheme_rules
    for THIS EMPLOYEE's scheme:
      - is this category allowed at all?
      - which category does the base calculation use?
      - what coefficient applies?

work_code_category_mappings
    given the CONTEXT of the work (night shift, weekend, parallel machines):
      - what derived category does the SOURCE category produce?
```

### Order of evaluation — this IS the business rule

```
1. contextual mapping, from the SOURCE category   (night → weekend chain)
2. then the scheme rule, on WHAT THE MAPPING PRODUCED
   → that result is the FINAL category of the pay row
```

The mapping lookup is always keyed on the **source** category, so a fixed
coefficient never deletes a night mapping — the mapping still fires, and is
still recorded on the log and the shift. What the scheme decides is what the
resulting row is *worth*.

**Consequence, and it is easy to miss:** the rule set must cover the mapping
**targets**, not only the categories a user can select. `JB`, `DB`, `GB`, `ZB`,
`L3`, `LP3` and `PLB` are produced by mappings and never chosen by anyone, yet
each needs a rule — otherwise a foreign employee's night shift maps `J → D` and
lands on a category the scheme has no answer for.

The rule set is also closed under its own output: `S → S`, so a second pass can
never change the result.

### Rows that collapse

Under the restricted scheme a shift's ordinary work, its `PL` work and the `PLB`
portion can all resolve to `S`. `daily_report_categories` is UNIQUE on
`(daily_report_id, work_code_category_id)`, so `DailyRecalcService` **merges**
them into one row: minutes and quantities add up, and the performance
coefficients are re-derived as a minute-weighted average.

---

## 5. Resolution algorithm

`WorkCategoryResolutionService` is the **single source of truth**. Every entry
point — the allowed-category API, work-log validation, creation, editing, the
daily recalc engine, payroll — goes through it or through an immutable snapshot
it produced. There is **no `if (employee.isForeigner())` anywhere** in the
calculation path.

1. **Source category** — from the operation or the work record. Taken as given,
   never replaced.
2. **Scheme** — the employee's scheme on the **work date**. Exactly one must
   apply.
3. **Rule** — the in-force rule for (scheme, source category, work date). With no
   rule, `allow_unmapped_categories` decides.
4. **Coefficient**, in precedence order:
   1. the rule's `coefficient_override`, when present;
   2. otherwise the existing normal logic (`work_code_categories.norm_multiplier`).

   Always `BigDecimal`, never binary floating point.
   `BigDecimal.valueOf(double)` goes through `Double.toString`, so `1.2` becomes
   exactly `1.2`.
5. **Contextual mappings run FIRST**, from the source category; the scheme rule
   in steps 3–4 is then applied to the mapping's result. See §4.

### Two coefficients, deliberately

| Used for | Resolved on |
|---|---|
| `work_logs.norm_multiplier_snapshot` → verified minutes, PL/PLB weighting | the **source** category |
| the pay row's coefficient → bonus-eligible minutes | the **final** (post-mapping, post-scheme) category |

This split is not new — it is what the system already did, and preserving it is
what keeps standard payroll numerically identical. Under the restricted scheme
both resolve to 1 anyway.

### Batching

Resolving a payroll month one log at a time would issue two queries per log.
`contextFor(employeeId, workDate)` loads the scheme and its whole rule set in
**two queries** and answers any number of categories from memory. The daily
recalc engine and the work-log batch endpoint both use it.

---

## 6. Worked examples

### A. Standard employee

```
employee scheme:      STANDARD
source category:      J   (I, II smena, norm_multiplier 1.0)
effective category:   J   (no rule, scheme is open by default)
coefficient:          1.0 (the existing standard coefficient)
actual shift:         NIGHT
contextual mapping:   J -> D applies, exactly as before
base report row:      D   (the mapped category — unchanged behaviour)
```

Nothing about a standard employee's payroll changes. This is not an aspiration:
when the scheme does not remap, the base row is the mapped category and the
coefficient is the category's own multiplier, i.e. the identical code path that
existed before.

### B. Fixed-coefficient employee

```
employee scheme:      FOREIGN_FIXED_COEFFICIENT
source category:      J   (I, II smena, norm_multiplier 1.0)
actual shift:         NIGHT
contextual mapping:   J -> D fires, from the SOURCE category, and is recorded
                      on the log and the shift as before
scheme rule on D:     D -> S, coefficient_override 1
final report row:     S at coefficient 1
```

The same category `D` is worth `1.2` under `STANDARD` and `1` under
`FOREIGN_FIXED_COEFFICIENT`, on the same day, for two different employees.

Note that the rule that fires is the one on `D` — the mapping's *target* — not
the one on `J`. That is why §7 lists the bonus categories.

---

## 7. Which categories the restricted scheme allows

Authoritative set, from the business, seeded by `2026-07-27-09`, effective
**2026-08-01**:

| Source | → | `coefficient_override` | Note |
|---|---|---|---|
| `J`, `D`, `G`, `Z`, `L`, `LP`, `PL` | `S` | `1` | what an employee selects |
| `JB`, `DB`, `GB`, `ZB`, `L3`, `LP3`, `PLB` | `S` | `1` | what the **mappings produce** |
| `S` | `S` | `1` | closes the set under its own output |
| `SO`, `B`, `B30`, `BP`, `ND`, `GO`, `NO` | *(unchanged)* | *(none)* | statutory, passes through |
| `PLO` | — | — | **explicitly denied** |

So a fixed-coefficient employee's payroll can only ever contain **`S`** plus
`SO`, `B`, `B30`, `BP`, `ND`, `GO` and `NO`.

`PLO` is an explicit `is_allowed = false` row rather than a missing one. The
scheme is closed by default so omitting it would have the same effect, but then
the data could not tell a decision apart from an oversight.

`S` is **allowed but not selectable** — two questions, two flags, because one
boolean cannot answer both:

| Flag | Question | For `S` |
|---|---|---|
| `is_selectable` | may a supervisor CHOOSE this when entering work? | **no** |
| `is_allowed` | may the calculation RESOLVE to this at all? | **yes** |

Work *becomes* `S` after the mapping, so it keeps its coefficient and its
self-mapping and can still carry a payroll row — but nobody performs it, so it
never appears in the picker and is rejected on submission.

`is_selectable` defaults to `TRUE`, so only exclusions are ever written.

> Expressing this with `is_allowed` alone was tried twice and was wrong both
> times: denying it removed the `S → S` definition, allowing it put it back in
> the dropdown.

**A restricted employee's picker therefore shows 21 categories** — the fourteen
work ones plus `SO`, `B`, `B30`, `BP`, `ND`, `GO`, `NO` — labelled with the bare
code, exactly as for any other employee. The scheme changes *which* codes are
offered, never how they are written: the supervisor enters what was worked and
the mapping is the backend's business.

### `valid_from` on a rule is NOT a rollout date

A rule says **what the scheme means**. The scheme PERIOD says **who is under it
and when**. Only the period is a rollout date.

The rules were first dated from the 2026-08-01 backfill cutover, and an
administrator then assigned a scheme period from 2026-07-01. For that month the
scheme was in force with no rules in force, and since it is closed by default it
refused every category — an empty work-entry dropdown and no error anywhere.

`2026-07-27-13` starts every rule at a computed baseline (the earlier of
2020-01-01 and the earliest scheme period), so a period assigned further back
cannot reopen the gap. `WorkCategoryResolutionService` also logs a warning when a
closed scheme has no rules in force at all, because that state is otherwise
indistinguishable from "correctly refused everything".

### The common category is called `S`

Created by `2026-07-27-03` as `FOREIGN_ALL_SHIFTS`, renamed through the
application, and converged on `S` by `2026-07-27-09`. Its Serbian name is
"I, II i III smena", English "1st, 2nd and 3rd shift".

> **Never key a lookup on a category code.** Codes are administrator-editable —
> this rename broke `03`'s idempotence, which resolved the category by code and
> would have raised on a re-run. Both scripts now resolve it by identity (the
> category an existing rule already points at) and fall back to either code.

---

## 7a. What a scheme changes on the payroll sheet

Beyond which work categories exist, a scheme controls two more things.

### Adjustment lines — `payroll_adjustment_category_scheme_rules`

Excluded under `FOREIGN_FIXED_COEFFICIENT`, from 2026-08-01:

| Code | |
|---|---|
| `MONTHLY_BONUS` | no bonuses under this scheme |
| `PHONE_CURRENT_MONTH`, `PHONE_PREVIOUS_MONTH` | not applicable |
| `MEAL_ALLOWANCE` | no meal allowance |
| `TRANSPORT_ALLOWANCE` | transport is not paid |

Everything else — `FIXED_SALARY`, `POSITIVE_NEGATIVE_CORRECTION`, `INSTALLMENT`,
`PAID_PREVIOUS_PERIOD`, `PREVIOUS_BALANCE`, `OTHER`, `PAID_PART_1`,
`PAID_PART_2` — is available.

> **This table defaults to ALLOW, the opposite of
> `work_code_category_scheme_rules`, and that is deliberate.** For a work
> category "no rule" means "unknown coefficient" and must be refused. An
> adjustment category is a labelled amount; closed-by-default would make every
> future adjustment category silently vanish for restricted employees, and a
> missing payslip line is far harder to notice than an extra one. So a row
> exists to say *no*.

**Meal allowance and transport are zeroed on the item, not merely unlinked.**
`totalNetEarnings` adds `item.total_meal_allowance_amount` and
`item.total_transport_allowance_amount` **directly**, not through the adjustment
line (the two are explicitly excluded from `additionsSum` to avoid
double-counting). Removing only the adjustment row would take the line off the
payslip while still paying the money.

### Performance bonus — `compensation_schemes.allows_performance_bonus`

`false` zeroes `payroll_run_item_categories.bonus_amount`.

> **Efficiency is not switched off by this.** Approved performance already
> weighted the minutes that became `weighted_norm_minutes` and therefore the
> category `amount`. Only the bonus paid *on top* is removed. A
> fixed-coefficient employee is still paid more for working faster; they simply
> get no bonus.

### Three questions, three answers

The same category gets a different answer depending on what is being asked, and
conflating any two of them is a bug. Under `FOREIGN_FIXED_COEFFICIENT`:

| Question | Answered by | `J` | `S` |
|---|---|:-:|:-:|
| May a supervisor **SELECT** it when entering work? | the rule's `is_selectable` | **yes** | no |
| May the calculation **RESOLVE** to it? | the rule's `is_allowed` | yes | yes |
| May it appear on the **PAYSLIP**? | `PayrollSchemeScopeService` | **no** | **yes** |

The third is the one that is easy to get wrong. `J` remaps to `S`, so after the
mapping nothing can ever accumulate against `J` — a row for it on the payslip
would be a permanent zero for work that by construction cannot land there. So:

> **A source category that remaps to a DIFFERENT category is not payable. Only
> its target is.** A self-mapping rule (`S → S`) is not a remap and stays
> payable, which is how the target earns its row. A pass-through rule (no
> effective category) keeps its own category payable, which is how `SO`, `B`,
> `GO` and the rest appear.

A fixed-coefficient employee's payslip therefore carries **`S`, `SO`, `B`,
`B30`, `BP`, `ND`, `GO`, `NO`** — and none of the fourteen categories that feed
into `S`.

### The row set and the amounts are guarded differently

`monthly_reports.version` tracks the report's CONTENT, so it is the right guard
for what a payroll row is **worth**. It says nothing about **which rows exist** —
that also follows the employee's compensation scheme, and moving an employee to
another scheme does not touch the monthly report at all.

Guarding both with the version produced exactly the failure you would expect: an
item refreshed before a scheme change looked up to date forever and never grew
the row its work now lands on. The payslip was missing `S` while the daily and
monthly reports both had it.

So the row set is reconciled first, on every read, unconditionally. It is a cheap
idempotent INSERT of what is missing, never a delete and never an amount. The
wanted set is the union of:

- **what the scheme says is payable** — so a category work maps INTO gets its row
  even in a month with no activity yet, which is what makes it a category this
  worker type *has*;
- **what the monthly report has activity in** — the money-safety net, applied
  whatever the scheme says, because a missing row here means those minutes never
  reach payroll at all.

If a row is added that the monthly report has activity for, the amounts are by
definition out of date and a recalculation follows. `LOCKED` items are skipped
entirely.

> `getForPayrollAccess` and `refreshIfStale` each keep their own copy of the
> staleness check — pre-existing duplication — so the reconciliation call had to
> go in both. Changing one without the other reintroduces this bug on one path
> only.

### What an excluded line does to the payslip

Three separate things, because "hidden" and "not counted" are not the same:

| | |
|---|---|
| **Excluded adjustment** | zeroed and un-applied at recalculation, then dropped from the response. Every total filters on `is_applied`, so it contributes nothing to any sum. |
| **Excluded work category** | empty rows are dropped from the response. Rows with real activity are **always shown**, whatever the scheme says today. |
| **Meal / transport** | zeroed on the item columns too — `total_net_earnings` adds those directly, so removing only the line would still pay the money. |

Rows are **neutralised, not deleted**. A run initialised before a scheme change
already has them; deleting is irreversible, and moving the employee back
restores the line with nothing lost.

> **A category the monthly report has activity in ALWAYS gets a payroll row**,
> created on the spot if the item lacks one — `populateItemCategoriesFromMonthlyReport`
> otherwise walks only the item's existing rows and those minutes vanish from
> payroll entirely. This is checked before any scheme filtering: the row exists
> because the work is real.

`LOCKED` payroll is never touched by any of this.

### A payroll month is a RANGE

`PayrollSchemeScopeService` answers the payroll question;
`WorkCategoryResolutionService` answers the work-date question. They differ
because an employee can change scheme inside a month, so every answer here is
the **union over every period overlapping the month**.

Union rather than intersection because the failure modes are not symmetric: too
generous shows a zero row, too strict makes recorded work vanish from the
payslip with nowhere to land. The seeded cutover falls on a month boundary, so
in practice one scheme governs a whole month and the unions are exact.

An employee with no scheme period for the month yields no scope, and callers
read that as unrestricted — payroll initialisation is not the place to refuse
somebody, and the work-date resolver already rejected anything they should not
have recorded.

### Net effect for a fixed-coefficient employee

Their payslip contains **`S`** plus `SO`, `B`, `B30`, `BP`, `ND`, `GO`, `NO`,
and no meal allowance, transport, phone or bonus line.

---

## 8. Snapshots and historical correctness

Each work log records enough to reconstruct its calculation **without reading the
employee's current scheme**:

| Column | Holds |
|---|---|
| `work_code_category_id` | the source category |
| `scheme_effective_work_code_category_id` | the scheme-effective category (`NULL` = same as source) |
| `compensation_scheme_id` | which scheme applied |
| `work_code_category_scheme_rule_id` | which rule produced the result |
| `norm_multiplier_snapshot` | the resolved coefficient |

`norm_multiplier_snapshot` already existed but had never been written by the
backend — only round-tripped from the client. It is now written from the
resolver, and the DTO value is ignored: a client must not be able to choose what
its own work is worth.

`ShiftIntervalResolver` reads this snapshot rather than the live category
multiplier, so the fast read path and the recalc engine consume the same
persisted value and cannot disagree, and historical logs are weighted with the
coefficient they were recorded under.

**Recalculation never re-prices old work from current rules.** Changing an
employee's scheme, or editing a rule, does not touch a snapshot for a period
outside the change.

---

## 9. Recalculation and locked payroll

Changing a scheme queues recalculation for **that employee only**, from the
**effective date onward**, through the existing daily queue — so the existing
debounce, retry and monthly-cascade behaviour applies unchanged. Earlier periods
resolve to the unchanged earlier scheme and are deliberately left alone.

Locked payroll is protected by the existing mechanism:
`PayrollRunItemService` never refreshes a `LOCKED` item, so a recalculated
monthly report cannot move a locked amount.

A recalc that resolves to the same values writes nothing — `audit_trigger_fn`
only records real changes, so an unchanged pass produces no audit noise.

A historical log whose category the scheme **no longer allows** is **not**
rejected during recalculation. The work was accepted when it was recorded, and
failing the job would wedge the queue rather than fix anything. It keeps its
snapshot and logs a warning.

---

## 10. How to

### Add a new compensation scheme

```sql
INSERT INTO compensation_schemes (code, name, allow_unmapped_categories, note)
SELECT 'MY_SCHEME', 'Ime šeme', false, 'Why this exists'
WHERE NOT EXISTS (SELECT 1 FROM compensation_schemes WHERE code = 'MY_SCHEME');
```

Resolve by `code`. Never insert a hard-coded id.

### Add a whole new worker type — data only, no code

Adding a scheme (seasonal worker, trainee, anything) is rows in three tables.
**No Java changes.** Exactly one place in `src/main` names a scheme at all —
`CompensationSchemeInitializer`, deciding which one a newly created employee
opens on — and nothing in the calculation path does.
`NewCompensationSchemeIsDataOnlyIT` builds a scheme this codebase has never
heard of and drives the whole path through it, so a future
`if (scheme.getCode().equals(...))` breaks the build.

```sql
-- 1. The scheme.
--    allow_unmapped_categories  false = only explicitly ruled categories usable
--    allows_performance_bonus   false = no bonus on top (efficiency still
--                                       weights the minutes themselves)
INSERT INTO compensation_schemes
    (code, name, allow_unmapped_categories, allows_performance_bonus, note)
SELECT 'SEASONAL', 'Sezonski radnik', false, false, 'Why this exists'
WHERE NOT EXISTS (SELECT 1 FROM compensation_schemes WHERE code = 'SEASONAL');

-- 2. Work-category rules. valid_from is NOT a rollout date — start it early
--    enough to cover any scheme period anyone might assign. See §7.
--    effective_category_id NULL  -> effective category IS the source
--    coefficient_override  NULL  -> the category's own norm_multiplier
--    is_selectable        false  -> reachable by the calculation, never offered
INSERT INTO work_code_category_scheme_rules
    (compensation_scheme_id, source_category_id, effective_category_id,
     is_allowed, is_selectable, coefficient_override, valid_from, note)
SELECT s.id, src.id, eff.id, true, true, 0.90, DATE '2020-01-01', 'Why'
FROM compensation_schemes s
JOIN work_code_categories src ON src.category_no IN ('J', 'D')
LEFT JOIN work_code_categories eff ON eff.category_no = 'S'
WHERE s.code = 'SEASONAL'
  AND NOT EXISTS (SELECT 1 FROM work_code_category_scheme_rules r
                  WHERE r.compensation_scheme_id = s.id
                    AND r.source_category_id = src.id);

-- 3. Payroll lines that do NOT apply. Absent = allowed, so list only exclusions.
INSERT INTO payroll_adjustment_category_scheme_rules
    (compensation_scheme_id, payroll_adjustment_category_id, is_allowed, valid_from, note)
SELECT s.id, c.id, false, DATE '2020-01-01', 'Why'
FROM compensation_schemes s
JOIN payroll_adjustment_categories c ON c.code IN ('MEAL_ALLOWANCE', 'MONTHLY_BONUS')
WHERE s.code = 'SEASONAL'
  AND NOT EXISTS (SELECT 1 FROM payroll_adjustment_category_scheme_rules r
                  WHERE r.compensation_scheme_id = s.id
                    AND r.payroll_adjustment_category_id = c.id);
```

Employees are then moved onto it through the existing employee screen, which
appends a dated period. **A checklist for a closed scheme:** every category a
supervisor may pick, every category the contextual MAPPINGS can produce from
those (§4), and the remap target itself with `is_selectable = false`.

There is deliberately no administration screen for this yet. It is the obvious
next step and nothing above blocks it — the tables already carry everything a
screen would write.

### Add a category rule

```sql
INSERT INTO work_code_category_scheme_rules
    (compensation_scheme_id, source_category_id, effective_category_id,
     is_allowed, coefficient_override, valid_from, note)
SELECT s.id, src.id, eff.id, true, 1, DATE '2026-09-01', 'Why'
FROM compensation_schemes s
JOIN work_code_categories src ON src.category_no = 'G'
LEFT JOIN work_code_categories eff ON eff.category_no = 'S'
WHERE s.code = 'FOREIGN_FIXED_COEFFICIENT'
  AND NOT EXISTS (
      SELECT 1 FROM work_code_category_scheme_rules r
      WHERE r.compensation_scheme_id = s.id
        AND r.source_category_id = src.id
        AND r.valid_from = DATE '2026-09-01');
```

- `effective_category_id` `NULL` → the effective category **is** the source.
- `coefficient_override` `NULL` → the **normal** coefficient logic applies.
- **Do not edit a rule that has already been used.** Close it
  (`valid_until`) and insert a new one, so historical snapshots stay explicable.

### Add an English translation

```sql
INSERT INTO work_code_category_translations (work_code_category_id, locale, name)
SELECT c.id, 'en', 'Electroplating'
FROM work_code_categories c
WHERE c.category_no = 'G'
  AND NOT EXISTS (SELECT 1 FROM work_code_category_translations t
                  WHERE t.work_code_category_id = c.id AND lower(t.locale) = 'en');
```

Or through the API: `PUT /api/work-code-categories/{id}/translations/en`, and for
payroll adjustment categories the `nameEn` field on the existing create/update
endpoints.

---

## 11. Translations

**Only two master/reference tables have a translation table:**

- `work_code_category_translations`
- `payroll_adjustment_category_translations`

Transactional tables — `payroll_adjustments`, `payroll_run_items`,
`payroll_run_item_categories` — carry **no** translated name. A payslip resolves
each name through the master row. Copying it onto every transactional row would
duplicate master data across thousands of records and guarantee they diverge the
first time someone corrects a typo.

There is no `english_transcription`, `name_en` or `english_name` column on any
transactional record. "Transcription" is also the wrong word: these are
translations, and nothing here transliterates.

### Fallback

```
COALESCE(translation.name, master.name)
```

A missing English row yields the Serbian name — **never null**, never a blank
label on a payslip. An unknown or blank requested locale falls back to the
default rather than failing the request.

`sr-Latn` is **not seeded**. It is served from `work_code_categories.category_name`
and `payroll_adjustment_categories.name`, so there is one place to edit a Serbian
name and nothing to drift apart. An explicit `sr-Latn` override row remains legal
if anyone ever needs one.

**Codes are never translated.** `category_no` and `code` are identifiers.

### Performance

Both name resolvers offer exactly one query shape — *every translation for this
locale* — because the read path is a payslip with a dozen categories and dozens
of adjustments. Callers load the map once and index it. Resolving per row inside
a payroll loop is the mistake these classes exist to prevent.

### Locale never affects a number

The translation maps are read **strictly after** the calculation. Every amount on
a payroll response is identical in every locale. Status codes (`DRAFT`,
`CALCULATED`, `APPROVED`, `LOCKED`, `CANCELLED`) stay stable in the database and
the API; only their display labels are localized, through frontend/PDF i18n, not
through database rows.

### Document language

A payroll PDF is a document **about the employee**, so it uses
`employees.preferred_locale`, not the preference of the clerk who opened it. An
optional `?locale=` parameter overrides it for preview.

---

## 12. API surface

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/compensation-schemes` | Assignable schemes |
| `GET` | `/api/employees/{id}/compensation-scheme-history` | One employee's periods |
| `POST` | `/api/employees/{id}/compensation-scheme-history` | Append a period from a date |
| `GET` | `/api/employees/{id}/allowed-work-code-categories?date=&locale=` | Selectable **source** categories |
| `GET` | `/api/work-code-categories/active-work-code-categories?locale=` | Categories with `displayName` |
| `PUT` | `/api/work-code-categories/{id}/translations/en` | Set/clear the English name |
| `GET` | `/api/payroll-run-items/by-monthly-report/{id}/details?locale=` | Payroll detail, localized |

The allowed-category endpoint returns **source** categories, each carrying the
effective category and coefficient it resolves to. It never returns only the
common effective category — that is a calculation target, not something anyone
worked.

**The backend always revalidates the submitted category.** Having appeared in a
dropdown earlier is not evidence that a category is still valid for the employee
and date now being submitted.

---

## 13. Probation withholds the weekend bonus

An employee inside their probation period is paid **no Saturday/Sunday bonus**.
The night-shift and multiple-machines remaps are unaffected — they apply from the
first day.

### Why this is NOT a compensation scheme

It was considered and rejected, for reasons that apply to anything shaped like
it:

- **A scheme is ASSIGNED; probation is DERIVED.** A scheme is a dated period in
  `employee_compensation_scheme_history`, and exactly one must cover every work
  date or the calculation refuses the day (§3). Probation comes out of the
  employment dates. As a scheme, somebody would have to open a period on hire and
  close it when probation ends, by hand, for every employee — and forgetting the
  second half breaks work **entry**, not merely a bonus.
- **Schemes are mutually exclusive; probation crosses them.** A foreign worker
  can be on probation. It would need `STANDARD_PROBATION`,
  `FOREIGN_FIXED_COEFFICIENT_PROBATION`, `COMMERCIAL_PROBATION`, and every future
  scheme would double the set — destroying the "add a worker type with data
  alone" property of §10 that makes schemes worth having.
- **Different questions.** A scheme answers what work is **worth**. A mapping
  answers what work **becomes**, given its context (§4). Probation belongs to the
  second, and that question already had exactly one home:
  `DailyRecalcService.resolveApplicableMappingTypes`, which already takes the
  employee and already gates `WEEKEND_BONUS` on the 180-minute weekly rule.

### `work_code_category_mapping_types` — the registry

`2026-09-15-01`. One row per remap kind, carrying `applies_during_probation`:

| `code` | `applies_during_probation` |
|---|:-:|
| `NIGHT_SHIFT_BONUS` | `TRUE` |
| `MULTIPLE_MACHINES_BONUS` | `TRUE` |
| `WEEKEND_BONUS` | **`FALSE`** |

The flag is on the **type**, not on each mapping row: the rule is "no weekend
bonus on probation", not "`J → JB` does not fire". Per row it would be four rows
(`J→JB`, `D→DB`, `G→GB`, `Z→ZB`) somebody has to keep in step, and a fifth added
later would default to the wrong answer. `DEFAULT TRUE` means a new type can
never silently withhold a bonus nobody meant to withhold.

**The registry also closes an older hole.** `mapping_type` was a bare `VARCHAR`
and the switch in `DailyRecalcService` ends in
`default -> { /* unknown mapping type: ignore */ }` — so a typo produced a mapping
row that looked configured and did nothing. `fk_wccm_mapping_type` makes that
impossible.

### `ProbationPolicy` — who, and when

Probation runs from `employment_start_date` to `probation_end_date`
(`= employment_start_date + norm_grace_days`), **both inclusive**, and is asked
**by work date, never by today** — the same discipline the scheme resolver
follows, so reopening an old month cannot re-decide it against the current
calendar.

> **Zero grace days is NO probation, not a one-day probation.** With
> `norm_grace_days = 0` the generated end equals the start, so arithmetic alone
> would put the first day inside the period. A returning employee is given zero
> precisely to say the opposite, and having it cost them a bonus on their first
> day back would invert the rule.

Missing data — no employee, no start date — resolves to **not** on probation.
This decides whether to WITHHOLD money, so the safe direction is to pay.

**In force always**, not from a date (owner's decision). Contextual mappings are
recomputed on every recalculation by design, so this applies to historical months
as they are recalculated. When it landed that changed nothing: no employee was on
probation and the database held no weekend shift worked during one.

### `ProbationPolicy` is the seam for employment periods

Employment is one start and one end on the employee row today, but an employee
can leave and return, so it is becoming a table of periods each carrying its own
`norm_grace_days`. When that lands, **only `ProbationPolicy.isOnProbation`
changes** — it looks up the period covering the work date instead of the employee
row — and nothing in the recalculation moves.

Note the interaction, because it is easy to get wrong: `employment_start_date` is
to become the start of the **latest** employment, and `probation_end_date` is
today a `GENERATED ALWAYS` column derived from it. Left there, a rehired employee
would automatically get a fresh 30-day probation — the opposite of the rule that a
returning employee gets `norm_grace_days = 0` by default. **The generated column
has to move onto the period row.**

### Not covered by an automated test

`ProbationPolicy` and the registry are covered by `ProbationWeekendBonusIT` (10
tests). The **wiring** — that `resolveApplicableMappingTypes` actually removes the
withheld type — is not: driving the real recalculation needs committed
`work_logs`, which needs products, operations and norms, and no fixture builds
them. The three lines that do the removal are in one place and visible, but this
is a gap and should be closed when a work-log fixture exists.

---

## 14. Employment is a history of periods

`2026-09-16-01`. An employee can leave and come back. As one start and one end on
the employee row a rehire either erased the first spell or needed a second
employee record — splitting one person's work, payroll and audit trail in two.

```
employee_employment_periods
  employee_id, started_on, ended_on, norm_grace_days,
  probation_end_date GENERATED ALWAYS AS (started_on + norm_grace_days)
```

`ex_eep_no_overlap` (GiST) makes it impossible to be employed twice at once —
the same device the scheme and payroll-value histories use, chosen over a
check-then-insert that two concurrent transactions could both pass.

### Why probation had to move with it

`employees.probation_end_date` was `GENERATED ALWAYS AS
(employment_start_date + norm_grace_days)`. Once "date of employment" means the
start of the **latest** spell — the owner's rule — that column would hand every
returning employee a fresh 30-day probation, contradicting the rule that a rehire
serves **none** by default.

On the period the same arithmetic is honest, because both inputs are columns of
the same row, so it stays `GENERATED` there: it cannot drift and it can be
queried.

> **On `employees` it is no longer generated.** Its value now comes from another
> table, and a generated column cannot be written by a trigger. `DROP EXPRESSION`
> converts it in place, keeping the values.

### The three mirrored columns

`employees.employment_start_date`, `employment_end_date` and `probation_end_date`
all survive as mirrors of the **latest** period, maintained by
`trg_eep_sync_employee`. A trigger rather than application code, so no write path
can forget them. 47 places read those columns — screens, filters, sorting,
projections — and none had to change.

The periods table is the authority; the columns are a view of it that existing SQL
and existing code can still use. `EmployeeDeactivationScheduler` needed no change
for the same reason: the mirrored `employment_end_date` is NULL for a rehired
employee, so they are not deactivated by their old leaving date.

### Grace days: 30 on the employee, 0 on a new period

| | Default | Means |
|---|:-:|---|
| `employees.norm_grace_days` | **30** | the length the FIRST period is opened with |
| `employee_employment_periods.norm_grace_days` | **0** | a rehire serves no new probation unless somebody says so |

`EmploymentPeriodService.openFirstPeriod` copies the employee's value into the
first period; every later one takes the column default. An administrator can set
a returning employee's grace days explicitly, and then they do serve one.

### Writing employment dates

**Only `EmploymentPeriodService` writes periods.** `EmployeeService`'s create,
update and patch paths all route through it — setting the mirror columns directly
would be overwritten by the next period change, and disagree with the periods
until then.

`applyEditedDates` edits the **current** period, which is what "edit the
employment dates" has always meant and still means for the single-spell case that
every employee is today. **Adding a spell is a different action** and deliberately
not that one: moving the current period's start to a rehire date would erase the
first spell, which is the thing this table exists to stop. There is no screen for
it yet — the table carries everything one would write.

### Not covered

There is no UI for adding or closing a spell, and no API endpoint. Until there is,
a rehire is two SQL statements: close the open period, insert a new one.
