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
