# Payroll component migration plan

Turning `payroll_adjustment_categories` / `payroll_adjustments` /
`payroll_adjustment_category_scheme_rules` into a **generic payroll component
model**, so that a new worker type or a new payslip line is configuration rather
than code.

Companion to
[`compensation-schemes-and-category-localization.md`](compensation-schemes-and-category-localization.md),
which holds the rules this plan must not break. Read that first.

Status: **Phases 0 and 1 complete.** Nothing is committed.

| Phase | State |
|---|---|
| 0 — safety net | ✅ `PayrollGoldenSnapshotIT` + `payroll-migration-diagnostics.sql` (Q1–Q18), run against production twice on 2026-07-31. Catalogue mirrored verbatim from Q12. |
| 1 — period-correct values | ✅ rates read at the payroll period; scheme scope resolved once per run. Verified to change **no** existing amount (Q18/Q18b). |
| 2 — employee payroll values | ✅ definitions + history + backfill, wired into the calculation with a fallback. Inclusive `valid_until`. |
| 3 — calculator registry | ✅ registry + meal/transport/manual calculators. **Transport is paid for the first time.** |
| 4 — single source of truth | ✅ earnings sum by impact; meal/transport read from their rows; F11 fixed; D9 constraint added |
| 5 — scheme rules | ✅ exactly one scheme per month; union deleted; `COMMERCIAL`; complete 39-pair matrix |
| 5b — restored business rules | ✅ bonus from `employees_bonus_history` + `bonus_min_hours_rules` + `bonus_eligibility_rules`; the two settlement lines compute; transport falls back to the company rate; lock/unlock built |
| 6 — generic DTO + frontend | ✅ backend and frontend. The UI renders from the scheme's answer and carries no worker-type branch. |
| 7 — clean-up | not started |

### Phase 0 findings that change later phases

Diagnostics run against production on **2026-07-31** (135 active employees, 2
schemes, 13 categories, 949 payroll items, 12 337 adjustments).

| # | Finding | Effect |
|:-:|---|---|
| **F1** | **The catalogue has drifted from its seed.** Five categories are no longer in `ADDITIONS`/`SETTLEMENTS`: `MEAL_ALLOWANCE`→`MEAL`, `PHONE_CURRENT_MONTH`→`PHONE`, `PAID_PREVIOUS_PERIOD`→`SETTLEMENTS_SUM`, `PREVIOUS_BALANCE`→`BALANCE`; and `PAID_PART_2`'s impact changed `PAYMENT_MINUS`→`DEDUCTION_MINUS`. Since `recalculateSummaryTotals` routes money by the literal strings `ADDITIONS` and `SETTLEMENTS`, **three lines reach no total at all**. | `PayrollScenarioFixture.CATALOGUE` corrected to production; new golden test `sectionCodeRoutesMoneyNotImpactCode` pins the routing. Phase 4's sign convention must preserve it. |
| **F2** | **No audit on any payroll table.** `audit_tables` has 37 rows; `payroll_adjustments`, `payroll_run_items` and `payroll_adjustment_categories` are absent. | D7 is false today. Phase 3 migration `2026-08-05-02` added as its prerequisite. |
| **F3** | **There is almost no hourly-rate data.** 2 of 135 employees carry `employees.hourly_rate`; only **26 of 949** payroll items have a positive rate, across **6 employees**; 923 items are calculated at rate 0. Of the 26, six are manual overrides. | D2's backfill is tiny and must read `hourly_rate_system` (the employee's rate as applied), **not** `hourly_rate` (post-override). See the revised D2 note. |
| **F11** | **`patch` does not sync the meal adjustment, and `allow_override` is decoration.** Editing the meal unit price recomputes `item.total_meal_allowance_amount` but never calls `updateAdjustmentByCategoryCode`, which the recalculation path does — so the two books disagree until the next recalculation. Separately, `MEAL_ALLOWANCE` and `TRANSPORT_ALLOWANCE` both carry `allow_override = false` and are edited anyway, because the patch goes through the item columns where the flag is never read. | Explains OPEN-4 completely. Harmless today only because the meal adjustment row reaches no total; **the same edit would silently fail to move money the moment Phase 4 makes the row authoritative.** Pinned by `mealPatchLeavesTheAdjustmentStaleUntilRecalculation` and `allowOverrideIsDecorativeForMealAndTransport`. |
| **F4** | **No payroll is locked.** All 949 items are `DRAFT`, back to 2023-01, and `getForPayrollAccess` recalculates any non-`LOCKED` item on read. | New risk **R9**. Historical months must be locked before Phase 3. |
| **F5** | **`total_gross_earnings` is always 0.** Nothing writes it after initialisation. | Pinned by the golden test; Phase 7 decides. |
| **F6** | **Transport is structurally 0.** `transport_allowance_days` is set to 0 and never computed. Q15 on the busiest month (2026-06) gives 23 qualifying shifts across 8 employees → **14 246 RSD** under D3, against **16 000 RSD** paid today by manual override. | Risk R1, and **the sample is far too small to size it**: 23 shifts for 135 employees is a near-empty month. At normal volume — 38 employees with a rate × ~22 shifts × ~620 RSD average — the figure is closer to **500 000 RSD/month**. Sign-off must be given on that number, not on 14 246. |
| **F7** | **Two shift constraints bound D3**, recorded there. | Golden test pins both. |
| **F8** | **`STANDARD` has zero scheme rules; 21 of 26 scheme × category pairs have none.** | D6's backfill is larger than it looks and must reproduce today's ALLOW default exactly. |
| **F9** | **Transport rates range 5.00–6000.00 RSD.** A 5 RSD per-arrival rate is not a rate. | Data clean-up before the D2 backfill — diagnostic Q14. |
| **F10** | ✅ **Decided: `employee_payroll_value_history` uses INCLUSIVE `valid_until`**, matching `employee_compensation_scheme_history`. `app_settings` is deliberately **not** aligned — see the section below for why, and for the narrower fix that is. |
| ~~F10 (original)~~ | **The codebase has two date-range conventions.** `ex_app_settings_no_overlap` uses a half-open `tstzrange(valid_from, COALESCE(valid_until,'infinity'))` — `valid_until` exclusive — while `ex_ecsh_no_overlap` uses `valid_until + 1`, i.e. inclusive. `findNumericSettingAt` then filters `valid_until >= :at`, inclusive, against the half-open constraint. | Benign today: at the boundary instant both rows match and `ORDER BY valid_from DESC LIMIT 1` picks the newer, which is correct. But **D2 must pick one convention and say so in the migration**, or `employee_payroll_value_history` inherits the ambiguity. Recommended: inclusive `valid_until` with `+ 1` in the constraint, matching the scheme history, because that is what the business means by "until". |

| **F12** | 🔴 **`EmployeeCompensationSchemeService.changeScheme` was broken, and had no test.** It closes the open period with `save()` and inserts the new one with `save()`. Hibernate orders INSERTs before UPDATEs inside a flush, and `GenerationType.IDENTITY` forces the INSERT out immediately — so the old period was still open in the database when the new one arrived and `ex_ecsh_no_overlap` rejected it. **Every scheme change for an employee who already had a period**, which is every employee. Found while writing the identical close-then-open logic for Phase 2. | Fixed with `saveAndFlush` on the close, in both services. `EmployeeCompensationSchemeChangeIT` now covers the method — 4 of its 5 tests failed before the fix. Phase 5's D1 rules land in that class. |

Clean results, recorded so they are not re-checked: **0** duplicate adjustments
(F→D9 is safe), **0** overlapping scheme assignments, **0** payroll months
resolving to more than one scheme, **0** employees without a scheme, **1**
legacy/adjustment divergence (item 1625, 2023-01, meal 700.00 vs 600.00).

The 128 scheme periods not starting on the 1st are all backfilled first
assignments at `employment_start_date`, which D1 explicitly permits. None of them
produces a two-scheme month.

---

## 0. Binding decisions

These were decided by the business owner and are not open for re-litigation
during implementation. Every phase below is written to honour them.

### D1 — A compensation scheme applies to a WHOLE payroll month

> A scheme change takes effect on the **first day of the following month**. An
> employee has **exactly one** effective scheme in any payroll month.

Consequences:

- **No** `payroll_run_item_scheme_segments`, **no**
  `payroll_component_result_parts`, **no** proration between two schemes.
- `PayrollSchemeScopeService` must resolve **exactly one** scheme per employee
  per payroll period:
  - **0 effective schemes → error.** Not "unrestricted".
  - **>1 effective scheme → error.** Not "pick the first", not "union".
  - exactly 1 → that scheme's configuration is used verbatim.
- On a scheme change the service must:
  1. keep the current assignment open until the last day of the current month;
  2. give the new assignment `valid_from` = first day of the next month;
  3. never produce overlapping periods;
  4. refuse (or replace through an explicit, named operation) when a future
     change is already scheduled;
  5. never alter a LOCKED payroll item.
- **Exception:** a *new* employee's *first* assignment may start on their
  employment start date — it is not a "change".

> ⚠️ **This reverses the currently documented and tested behaviour.**
> `PayrollSchemeScopeIT.midMonthChangeUnionsBothSchemes` and
> `PayrollSchemeScopeIT.noSchemeYieldsNoScope` encode the old union / permissive
> rules and must be rewritten in Phase 5, not silently deleted. The comments in
> `PayrollSchemeScopeService` and in the companion doc must be updated in the
> same commit.

### D2 — Employee payroll values: catalogued definitions + history (variant B)

Two tables, not one generic key/value table:

- `employee_payroll_value_definitions` — the **catalogue** of value types the
  system knows (`HOURLY_RATE`, `TRANSPORT_RATE`, `FIXED_LD_AMOUNT`,
  `TELEPHONE_AMOUNT`, `BONUS_PERCENTAGE`). `code` is unique. A calculator may
  not invent a `value_key`; it must reference a registered definition.
- `employee_payroll_value_history` — the **values**, date-effective, with a
  no-overlap exclusion constraint per `(employee, definition)`.

`value_type` supports at least `NUMERIC`, `BOOLEAN`, `TEXT`; a check constraint
must guarantee only the matching column is populated.
`payroll_adjustment_category_id` on the *definition* is nullable — `HOURLY_RATE`
is a calculation input, not a payslip line.

Values are never `UPDATE`d in place. The service exposes one transactional
operation: **close the current period, open the new one**.

> 🔴 **Phase 0 finding F3 changes the backfill source.** Only **2 of 135**
> employees carry `employees.hourly_rate`; the other 133 are `NULL`. And
> `PayrollRunItemService:863` guards on null — `if (…getHourlyRate() != null)` —
> so for those 133 the system rate is never refreshed and the rate actually used
> is whatever sits on `payroll_run_items.hourly_rate`, entered per month.
>
> Backfilling `HOURLY_RATE` from `employees.hourly_rate` would therefore give
> almost every employee no rate at all, and Phase 3's calculators would read zero.
>
> **The Phase 2 backfill reads `payroll_run_items.hourly_rate_system` per period**
> — the employee's rate as it was actually applied — collapsing consecutive equal
> values into one date-effective row. Q13c settles why it must not be
> `hourly_rate`: employee 2 shows system 460 throughout with `hourly_rate` 500 in
> 2026-01 and 2026-06 and back to 460 in 2026-07, all flagged
> `hourly_rate_overridden`. Those 500s are **per-month decisions, not rate
> history**, and Phase 4 keeps overrides on the item where they belong. Migrating
> them into the rate history would turn two one-off decisions into a permanent
> raise and a permanent cut.
>
> The result is deliberately small: **6 employees get a history, 129 get none**,
> and 923 of 949 items keep calculating at rate 0 exactly as they do today. A
> backfill that produced more than that would be inventing data.
> `employees.hourly_rate` is the fallback for anyone with no payroll item.
>
> `TRANSPORT_RATE` migrates all 38 values verbatim (OPEN-5).

### D2a — Why `app_settings` is NOT aligned to the same convention

Asked in 2026-07-31: can `app_settings` be changed so `valid_until` everywhere
means "the last day the value applies"? **Investigated and deliberately declined**
— but a narrower, genuinely valuable fix was found and is specified below.

**The two tables are different kinds of thing.**

| | type | "last day it applies" |
|---|---|---|
| `employee_compensation_scheme_history` | `date` — **discrete** | natural: the next period starts the following day |
| `employee_payroll_value_history` (new) | `date` — **discrete** | same |
| `app_settings` | `timestamptz` — **continuous** | **not expressible** |

On a continuous type there is no "next value", so an inclusive upper bound has to
be written as `valid_until = next_valid_from - interval '1 microsecond'`. Every
boundary then becomes a hand-maintained 1 µs gap that nothing enforces. This is
exactly why PostgreSQL canonicalises `daterange` to `[)` and leaves `tstzrange`
alone: half-open is the correct convention for continuous types, inclusive for
discrete ones. **The current `app_settings` design is right; only its queries are
wrong.**

Converting the columns to `date` would make the alignment literal, but it drops
intra-day precision (a rate saved at 14:30 would take the whole day), touches an
audited table used well beyond payroll — `max_efficiency_percent` and others —
and is a project of its own. Not worth folding into this migration.

**The real defect, and the fix.** The constraint treats `valid_until` as
exclusive; all four repository queries treat it as inclusive:

```
ex_app_settings_no_overlap   tstzrange(valid_from, COALESCE(valid_until,'infinity'))   -- exclusive
AppSettingRepository x4      AND (s.valid_until IS NULL OR s.valid_until >= :at)        -- inclusive
```

At exactly the boundary instant both rows match. Benign today because every query
has `ORDER BY valid_from DESC` and picks the newer one — but it is a trap for the
next query written without it.

**Deferred migration `2026-08-20-01-app-settings-query-boundary.sql`** — after
Phase 2, independent of it:
- change the four queries to `s.valid_until > :at`
- **no data migration**: not one row moves, and no historical price shifts by a day
- precondition, verified by diagnostic **Q19/Q19b**: no key may have a CLOSED
  final period. With one, the value would stop applying one microsecond earlier
  than today; with an open final period (`valid_until IS NULL`) the branch never
  fires and the change is provably neutral. Q18 already shows both payroll keys
  have exactly one open-ended period since 2020, so the precondition holds for
  them; Q19 checks the rest.
- Q19c lists every touching pair, so the neutrality claim is checkable rather
  than asserted
- add a regression test: two adjacent periods, read at the exact boundary
  instant, must return the newer value both before and after

### D3a — Two transport modes  (decided 2026-08-02)

> **FIXED** — some employees have a fixed MONTHLY amount. They are paid it whole;
> attendance does not change it, so no day is counted.
>
> **PER DAY** — everyone else is paid for the days they actually worked, at the
> one company rate `app_settings.transport_allowance_per_day`.

These are MODES, not two prices for the same thing. Neither is a fallback for the
other: a fixed employee is not paid more for coming in more often, and a per-day
employee has no monthly figure to fall back to.

`employees.transport_allowance_mode` (`AUTO` / `FIXED`) is what says which mode
somebody is on. It has existed all along and the calculation never read it —
which is also why `employees.transport_allowance_rsd` looked like a per-arrival
rate when it is a monthly amount.

> ⚠️ **37 of 134 `AUTO` employees carry an amount** (Q5b). Under this rule those
> amounts are ignored, and the employee is paid per worked day. Each is either a
> wrong mode or a stale figure — diagnostic **Q20/Q20b** lists them. This is
> OPEN-18.

### D3 — What counts as a worked day

> One transport unit per **distinct work-shift record** in the month that has
> `work_minutes > 0`.

Resolved source (verified against the schema):

```sql
SELECT count(*)
FROM daily_reports dr
WHERE dr.employee_id = :employeeId
  AND dr.work_date BETWEEN :periodStart AND :periodEnd
  AND dr.total_work_minutes > 0
  AND dr.archived_at IS NULL;
```

Why this is exactly the rule:

- `uq_daily_reports_employee_shift UNIQUE (employee_id, work_shift_id)` — one
  `daily_reports` row **per work-shift record**, so `count(*)` is
  `count(DISTINCT work_shift_id)`.
- `daily_reports.total_work_minutes` is built in
  `DailyRecalcService.fillDailyTotals` as the sum of category minutes whose
  `sourceType` is `WORK` only — absence and sick leave are excluded, and it is
  **not** the planned shift duration (`total_shift_minutes` is that).
- A shift crossing midnight is still one `work_shifts` row with one
  `work_date`, so it is one unit.
- Several work logs inside one shift collapse into one `daily_reports` row, so
  it is one unit.

Two schema constraints tighten what "a distinct shift" can mean, both found in
Phase 0 and both now pinned by `PayrollGoldenSnapshotIT`:

- `ex_work_shifts_no_overlap` — one employee's shifts may not overlap in
  wall-clock time, so two shift records always mean two genuinely separate
  arrivals;
- `uq_work_shifts_employee_shift_work_date UNIQUE (employee_id, shift_id, work_date)` —
  the same shift **type** cannot be recorded twice on one day, so "two arrivals
  on one day" is representable only as two different shift types (I + II).

Calculator output:

```text
quantity    = number of qualifying shifts
unit_amount = the employee's TRANSPORT_RATE in force for the payroll period
amount      = quantity × unit_amount
```

The fixed monthly mode stays supported; the rule above governs the per-unit mode.

**No `TRANSPORT_RATE` in force means 0, and that is a correct answer, not an
error** (OPEN-7): some employees are paid transport and some are not, and the
presence of a rate is what distinguishes them. The calculator returns
`quantity = 0, amount = 0` with `calculation_inputs.reason = NO_RATE_CONFIGURED`
so the zero is explained rather than silent.

### D4 — Work area, worker status and contract type stay conceptually separate

```text
work area:      COMMERCIAL | PRODUCTION
worker status:  STANDARD   | FOREIGN
contract type:  PERMANENT  | SEASONAL
```

A **compensation scheme is the final payroll policy for a combination**
(`STANDARD_PRODUCTION`, `FOREIGN_PRODUCTION`, `SEASONAL_PRODUCTION`,
`STANDARD_COMMERCIAL`, `FOREIGN_COMMERCIAL`, …).

No classification engine and no new employee-classification tables are
introduced now. `is_foreigner` and `works_in_commercial` may be read **for the
initial backfill only**. Hard rules:

- no calculator reads `is_foreigner`;
- no calculator reads `works_in_commercial`;
- no frontend payroll branch reads either;
- every payroll rule comes from the effective scheme configuration.

### D5 — No union of scheme configurations

`PayrollSchemeScope` represents **one fully resolved configuration of one
scheme**. Batch loading may return `Map<employeeId, PayrollSchemeScope>`, but
configurations of different employees are never merged, and one employee never
gets a merged configuration. The `union(...)` helper is deleted.

### D6 — Every component needs an explicit rule per scheme

Neither "missing row = ALLOW" nor "missing row = DENY". **Missing row = incomplete
configuration = error.**

For every *active* adjustment category × every *active* compensation scheme
there must be a rule specifying at least:

```text
is_allowed
calculation_mode
visible_in_ui
visible_in_pdf
show_when_zero
editable_input
allow_total_override
required_manual_input
parameters_override
```

Lifecycle:

- a **new category** is created inactive/DRAFT → rules for every active scheme →
  only then may it be activated;
- a **new scheme** is created inactive → rules for every active category → only
  then may it be activated and assigned.

An unknown `calculation_key` is a hard error, never a silent zero.
A new `PayrollConfigurationValidationService` checks:
missing scheme×category rules · unknown calculation keys · illegal edit-policy
combinations · incomplete manual components · inactive categories/schemes still
in use · overlapping scheme assignments.

### D7 — No override-history table

Use the existing `audit_logs` + `audit_trigger_fn`. Verify the audit for
`payroll_adjustments` captures old value, new value, `is_overridden`,
`correction_amount`, `override_reason`, user and timestamp.

> 🔴 **Phase 0 finding: the audit does not exist yet.** `audit_tables` (dumped
> from production into `src/test/resources/db/reference-data.sql`) contains 37
> rows and **none** of them is `payroll_adjustments`,
> `payroll_adjustment_categories`, `payroll_adjustment_category_scheme_rules` or
> `payroll_run_items`. No trigger, no history. D7 does not hold today and cannot
> hold until those tables are registered — see the Phase 3 migration
> `2026-08-05-02-audit-payroll-tables.sql`. **This is a prerequisite for D7, not
> an optional extra:** deciding against an override-history table while no audit
> exists would leave payroll overrides with no history at all.

`override_reason` is **mandatory** on a hard total override. Editing a permitted
input (e.g. `unit_amount`) is **not** a hard override — the two must remain
distinguishable:

```text
edited a permitted input   → is_overridden = false, formula still runs
hard-overrode the total    → is_overridden = true,  formula bypassed, reason required
```

### D8 — Manual components may or may not require input

New flag `required_manual_input`, defaulted on the category, nullable-overridable
on the scheme rule (`NULL` = inherit, resolved with `COALESCE`).

| `calculation_mode` | `required_manual_input` | nothing entered |
|---|:-:|---|
| `MANUAL` | `true` | adjustment status `PENDING_INPUT`; payroll item **cannot be locked**; clear validation message |
| `MANUAL` | `false` | zero / empty is fine; item can be locked |

"Not entered" must be distinguishable from "user explicitly entered 0" — via a
`has_manual_input` flag or an adjustment status. `ManualCalculator` computes
nothing but must never mark a required empty line as successfully calculated.

### D9 — One row per category per payroll item

After a diagnostic pass and a data clean-up:

```sql
UNIQUE (payroll_run_item_id, payroll_adjustment_category_id)
```

Duplicates must **not** be auto-merged or auto-deleted: produce a report, work
out how each arose, and migrate only where the transformation is unambiguous.
No cardinality model, no multiple `OTHER` lines. The service layer must refuse a
second row of the same category as well.

### D10 — Keep the existing adjustment model

Do **not** introduce `payroll_components`, `payroll_component_versions`,
`payroll_component_results`, `payroll_calculation_strategies`,
`payroll_component_dependencies`, `payroll_run_item_scheme_segments`,
`payroll_component_result_parts`, `payroll_component_override_history`.

Extend the three existing tables instead. The calculator registry lives in Java.

```text
new worker type / new combination of existing rules  → scheme + SQL configuration
new payslip line using existing maths                → SQL configuration
new kind of maths                                    → new Java calculator
```

### D11 — Commercial and foreign

`COMMERCIAL` scheme:

```text
MONTHLY_BONUS
    is_allowed            = true
    calculation_mode      = ZERO
    visible_in_ui         = true
    visible_in_pdf        = true
    show_when_zero        = true
    allow_total_override  = false      -- OPEN-1, decided 2026-07-31
    editable_input        = 'NONE'     -- no correction either
    required_manual_input = false
```

> **OPEN-1 is decided: a commercial employee cannot be given a bonus by hand.**
> The line is printed at 0,00 and is not editable in any way — neither a
> correction nor a total override. The UI must render it read-only rather than as
> a disabled input that looks temporarily unavailable.

Foreign scheme:

```text
MEAL_ALLOWANCE       is_allowed = false, visible_in_ui = false, visible_in_pdf = false
TRANSPORT_ALLOWANCE  is_allowed = false, visible_in_ui = false, visible_in_pdf = false
```

Not calculated, not returned as visible lines, not rendered, not printed. There
must be no `if (employee.isForeigner())` in any calculator.

### Open questions

| id | question | status |
|---|---|---|
| OPEN-1 | May a commercial employee receive a **manual** bonus? | ✅ **No.** Decided 2026-07-31 — see D11. |
| OPEN-3 | Do duplicate adjustments exist in production? | ✅ **No** — 0 rows. D9's `UNIQUE` is safe to add. |
| OPEN-4 | Item 1625 (2023-01): meal 700.00 on the column, 600.00 on the adjustment. Which is correct? | ✅ **The item column, 700.00.** Q16 shows `meal_allowance_count = 2`, `unit_amount = 350` with `overridden = true` against a system rate of 300: a deliberate human decision, correctly multiplied. The adjustment kept `2 × 300 = 600` because `patch` never syncs it (finding F11). The item is also stale — `based_on_version 14` vs report version 20 — so the next read recalculates and writes 700 onto the adjustment. **No data migration: the divergence self-heals and the authoritative value is already right.** |
| OPEN-5 | Transport rates run 5.00–6000.00 RSD. Clean them up? | ✅ **No — keep them.** Decided 2026-07-31. All 38 migrate verbatim; the Q14 verdicts are heuristics, not rules. Consequence: whatever a rate means today, D3 multiplies it by the shift count, so an employee on 6000 with 20 shifts computes 120 000 RSD. That is the number Phase 3 sign-off has to accept. |
| OPEN-6 | Lock months before Phase 3? | ✅ **No.** Decided 2026-07-31. R9 is therefore accepted rather than removed — see the R9 row for the mitigation that does not need locking. |
| OPEN-13 | Nothing in the codebase ever locked a payroll item. | ✅ **Built.** `POST /api/payroll-run-items/{id}/lock` and `/unlock`, admin only. Locking recalculates first — freezing a stale item would make out-of-date figures permanent — then refuses if any required manual line is still empty, naming each. D8 is complete with it: `has_manual_input` separates "not entered" from "entered as 0", so an explicit zero unblocks the lock and silence does not. |
| OPEN-12 | Where is the current month's phone actually entered? | ✅ **The `payroll_run_items.current_month_telephone` COLUMN.** `PayrollCategoriesTable.tsx` writes `changeKey="currentMonthTelephone"`, `patch` sets the column, and `propagateToNextMonthItem` reads it to fill next month's `PHONE_PREVIOUS_MONTH`. The `PHONE_CURRENT_MONTH` adjustment line has sat at zero throughout — the same split F11 found on meal. The patch now syncs the line from the column, so the payslip shows the figure; the line still reaches no total, because this month's phone is deducted NEXT month. Phase 7 inverts it: the line becomes authoritative when the column is dropped. |
| ~~OPEN-12 (original)~~ | Should `PHONE_CURRENT_MONTH` and `PAID_PREVIOUS_PERIOD` ever reduce pay? Today neither reaches any total: the current month's phone is deducted NEXT month as `PHONE_PREVIOUS_MONTH`, and `PAID_PREVIOUS_PERIOD` mirrors a figure `previous_net_payable_amount` already carries. Switching the settlements side to impact codes would start deducting both. | 🔴 **blocks the settlements half of the impact-code switch.** Phase 4 moved only the earnings side, which is provably identical; settlements stays on `section_code` until this is answered. |
| OPEN-8 | The automatic base of `MONTHLY_BONUS`. | ✅ **Answered 2026-08-01, and it was never `payroll_run_item_categories.bonus_amount`.** Two independent parts: the employee's own category amount (`employees_bonus_history` → `bonus_categories.bonus_amount`), paid IN FULL only if they worked at least `bonus_min_hours_rules.min_num_hours` that month; plus the highest `bonus_eligibility_rules` hour tier reached, which adds its `bonus_value`. Both keyed by period. Implemented as `MonthlyBonusCalculator`. |
| OPEN-9 | `PAID_PREVIOUS_PERIOD`. | ✅ **Not a mirror — it is the settlements TOTAL.** `INSTALLMENT + PHONE_PREVIOUS_MONTH + PAID_PART_1 + PAID_PART_2`, which is exactly what `recalculateSummaryTotals` already computes as `previously_paid_amount`; the balance is then earnings − this figure. The line shows that total, so the two cannot drift. Its section `SETTLEMENTS_SUM` literally names it, and keeping it out of the settlements sum is what stops everything being deducted twice. |
| OPEN-10 | `PREVIOUS_BALANCE`. | ✅ Last month's closing balance, already on the item as `previous_net_payable_amount` and already inside `net_payable_amount`. The line shows it so the payslip can explain how the amount to pay was reached. Section `BALANCE` reaches no total, correctly — adding it would count it twice. |
| OPEN-11 | `app_settings.transport_allowance_per_day`. | ✅ **It is the company PER-DAY rate, and it is the only rate.** There is no per-employee per-day price. |
| **OPEN-15** | **The per-day mode reads no per-employee value, so no start date can hold it back.** Every employee not on the fixed mode is paid for every month in which they worked — historical months included — the next time one is opened. The Phase 2 start date controls the FIXED mode only. | 🟡 **Parked 2026-08-02: nothing is going to production yet and nothing is being locked.** Not a blocker while the work is in the tree. It becomes one on the day this is first deployed — at that point either lock up to a cut-off month, or accept that historical months gain transport. The lock operation exists either way (OPEN-13). |
| ~~OPEN-14~~ | ✅ **Answered by the code, and my first implementation was wrong.** `saturday_count` is the TIER ORDINAL, not a second dimension: `BonusCalendarSyncService.syncEligibilityActiveFlags` derives each rung from the work calendar as `min_num_hours = (workdays + ordinal) * 8` and sets `is_active = false` where the month has fewer working Saturdays than the ordinal. Rung 1 is always inactive. So every rule carries a `saturday_count`, and the calculator's "skip any rule that has one" filter silently discarded **all** of them — no tier bonus would ever have been paid. Fixed: filter on `is_active`, take the highest `min_num_hours` reached. |
| **OPEN-16** | `MonthlyBonusCalculator` measures "hours worked" as `payroll_run_items.total_work_minutes` — worked minutes, excluding paid absence and not the shift duration. Confirm that is the figure the thresholds are meant to compare against, rather than `total_payroll_minutes` (work + the manual adjustment). | 🟡 open, low risk — the two differ only where an administrator has adjusted the minutes by hand. |
| OPEN-2 | Employee with **no** scheme period — hard error at initialisation, or at calculation? | 🟡 open. Production currently has 0 such employees, so either choice is safe to ship. Default: error at both; initialisation refuses to create the item. |
| OPEN-7 | From which month should transport actually start being paid? | ✅ **There is no such month.** Decided 2026-07-31: some employees are paid transport and some are not, 0 and non-0 are both correct outcomes, and where it applies the real start date varies — January 2025 for some. **The employee's `TRANSPORT_RATE` is the switch**: a rate in force means transport is computed, no rate means 0. So the control is per employee, not per date — see R9. |

---

## 1. SQL migrations by phase

Filenames follow the existing `YYYY-MM-DD-NN-name.sql` convention in
`src/main/resources/sql/`. They are applied in filename order by
`AbstractIntegrationTest`, so every one must be re-runnable.

### Phase 0 — no migrations

Read-only diagnostics only:
`docs/business-rules/payroll-migration-diagnostics.sql`.

### Phase 1 — no migrations

Java-only (period-correct reads).

### Phase 2 — employee payroll values

**`2026-08-01-01-employee-payroll-value-definitions.sql`**
- `CREATE TABLE employee_payroll_value_definitions`
  (`code` unique, `name`, `description`, `value_type`, `unit_code`,
  `payroll_adjustment_category_id` nullable FK, `is_system`, `is_active`,
  timestamps, `archived_at`)
- `CHECK value_type IN ('NUMERIC','BOOLEAN','TEXT')`
- `CHECK` non-empty `code` / `name`; `CHECK` no reactivate after archive
- seed: `HOURLY_RATE`, `TRANSPORT_RATE`, `FIXED_LD_AMOUNT`, `TELEPHONE_AMOUNT`,
  `BONUS_PERCENTAGE` (all `NUMERIC`, `is_system = true`)
- register in `audit_tables` + audit trigger + `set_updated_at` trigger

**`2026-08-01-02-employee-payroll-value-history.sql`**
- `CREATE TABLE employee_payroll_value_history`
  (`employee_id`, `value_definition_id`, `numeric_value`, `boolean_value`,
  `text_value`, `valid_from`, `valid_until`, `note`, `created_by`, timestamps,
  `archived_at`)
- `CHECK` exactly-one-value-matching-`value_type` (correlated via a trigger or a
  denormalised `value_type` column — decide during implementation; a
  denormalised column kept in sync by FK+trigger is the simpler correct option)
- `CHECK (valid_until IS NULL OR valid_until >= valid_from)`
- `EXCLUDE USING gist (employee_id WITH =, value_definition_id WITH =,
  daterange(valid_from, CASE WHEN valid_until IS NULL THEN NULL ELSE valid_until + 1 END) WITH &&)
  WHERE (archived_at IS NULL)` — same half-open convention as `ex_ecsh_no_overlap`
- index `(employee_id, valid_from DESC) WHERE archived_at IS NULL`
- audit registration

**`2026-08-01-03-employee-payroll-value-backfill.sql`**
- **`HOURLY_RATE` comes from `payroll_run_items.hourly_rate` per period**, not
  from `employees.hourly_rate` — finding F3. Consecutive periods with the same
  rate collapse into one row; each change opens a new one. Employees with no
  payroll item fall back to `employees.hourly_rate` where it exists.
- `employees.transport_allowance_rsd` → `TRANSPORT_RATE`, **excluding the values
  OPEN-5 rejects** as bad data. A rejected employee gets no row, so the phase 3
  calculator returns 0 with `reason = NO_RATE_CONFIGURED` rather than paying a
  wrong amount.
- transport mode (`employees.transport_allowance_mode`) → the basis carried by
  the value row / definition, per its current meaning. Production has 134 `AUTO`
  and 1 `FIXED`.
- `valid_from` = earliest defensible date: the first payroll period the rate
  appears in, else `employment_start_date`
- `note` explicitly records that pre-migration history is unknown
- **legacy columns are NOT dropped** — they stay as a read-only mirror
- final `DO $$` block reporting how many employees ended with no `HOURLY_RATE`
  and no `TRANSPORT_RATE`, so a silent under-backfill is impossible to miss

### Phase 3 — calculator configuration

**`2026-08-05-01-payroll-category-edit-policy.sql`**
```sql
ALTER TABLE payroll_adjustment_categories
    ADD COLUMN editable_input        varchar(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN allow_total_override  boolean     NOT NULL DEFAULT false,
    ADD COLUMN show_when_zero        boolean     NOT NULL DEFAULT true,
    ADD COLUMN required_manual_input boolean     NOT NULL DEFAULT false,
    ADD CONSTRAINT chk_pac_editable_input
        CHECK (editable_input IN ('NONE','AMOUNT','UNIT_AMOUNT','QUANTITY','CORRECTION'));

ALTER TABLE payroll_adjustments
    ADD COLUMN correction_amount numeric(38,2) NOT NULL DEFAULT 0,
    ADD COLUMN override_reason   text,
    ADD COLUMN has_manual_input  boolean NOT NULL DEFAULT false,
    ADD COLUMN status            varchar(30) NOT NULL DEFAULT 'CALCULATED',
    ADD COLUMN calculation_inputs jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN calculated_at     timestamptz,
    ADD CONSTRAINT chk_pa_status
        CHECK (status IN ('CALCULATED','PENDING_INPUT','MANUAL','OVERRIDDEN','ERROR'));
```
plus `UPDATE`s that set `editable_input` / `allow_total_override` /
`calculation_key` per category (table in §1.1 below).

`chk_pa_override_reason` is deliberately **not** added here — `is_overridden`
still carries the old meaning until Phase 4c.

**`2026-08-05-03-lock-closed-payroll-months.sql`** — prerequisite for Phase 3,
risk R9, blocked on OPEN-6.
- `UPDATE payroll_run_items SET status = 'LOCKED', locked_at = now(), locked_by = :userId
   WHERE period <= :lastClosedMonth AND status <> 'LOCKED' AND archived_at IS NULL`
- the cut-off month is a business decision, not a default in this file
- report the count and the summed `net_payable_amount` frozen
- verify afterwards that `getForPayrollAccess` returns those items unchanged —
  [PayrollRunItemService.java:156](../../src/main/java/com/aleksandarparipovic/marel_app/payroll_run_item/PayrollRunItemService.java:156)
  already short-circuits on `LOCKED`, so no code change is needed

**`2026-08-05-02-audit-payroll-tables.sql`** — prerequisite for D7, see the
Phase 0 finding there.
- `INSERT INTO audit_tables` for `payroll_adjustments`,
  `payroll_adjustment_categories`,
  `payroll_adjustment_category_scheme_rules`, `payroll_run_items`
- `CREATE TRIGGER trg_audit_logs_<table> AFTER INSERT OR UPDATE OR DELETE …
  EXECUTE FUNCTION audit_trigger_fn()` for each
- update `src/test/resources/db/reference-data.sql` in the same commit, or every
  integration test that writes to those tables fails on
  `audit_logs.table_id` NOT NULL
- **write volume check before shipping:** `payroll_run_items` is rewritten on
  every lazy recalculation (`getForPayrollAccess`), so auditing it row-by-row is
  materially different from auditing a catalogue. Measure on one payroll month
  and, if it is noisy, audit `payroll_adjustments` only — that is the table D7
  actually needs.

### Phase 4 — single source of truth

**`2026-08-12-01-payroll-adjustments-override-semantics.sql`**
- `UPDATE` rows where only `unit_amount`/`quantity` differ from the system value
  → `is_overridden = false` (the new meaning: hard total override only)
- `ADD CONSTRAINT chk_pa_override_reason CHECK (is_overridden = false OR
  (override_reason IS NOT NULL AND length(trim(override_reason)) > 0))`
- add the display snapshot columns
  (`section_code_snapshot`, `impact_code_snapshot`, `sort_order_snapshot`,
  `visible_in_ui_snapshot`, `visible_in_pdf_snapshot`, `show_when_zero_snapshot`)
- `COMMENT ON COLUMN` on the `payroll_run_items` meal/transport/bonus columns
  marking them a read-only mirror

**`2026-08-12-02-payroll-adjustments-singleton.sql`** (D9)
- ✅ **cleared** — the 2026-07-31 diagnostic found 0 duplicates (OPEN-3), so no
  data clean-up is needed
- `ALTER TABLE payroll_adjustments ADD CONSTRAINT uq_payroll_adjustment_item_category
  UNIQUE (payroll_run_item_id, payroll_adjustment_category_id)`
- keep the guard re-check in the migration anyway: it runs against whatever the
  database looks like on the day it is applied, not on the day it was written

> ✅ **OPEN-4 is closed and needs no migration** — the item column is correct and
> the divergence self-heals on the next recalculation. But it exposed **F11**, and
> that does need fixing here: `patch` must sync the adjustment row, or making the
> row authoritative turns "edit the meal price" into a no-op. The Phase 4 change
> that removes the item columns removes the desync structurally; the golden tests
> `mealPatchLeavesTheAdjustmentStaleUntilRecalculation` and
> `allowOverrideIsDecorativeForMealAndTransport` must be rewritten to the new
> behaviour in the same commit, not deleted.

### Phase 5 — scheme rules

**`2026-08-15-01-scheme-rule-presentation.sql`**
```sql
ALTER TABLE payroll_adjustment_category_scheme_rules
    ADD COLUMN calculation_mode      varchar(20) NOT NULL DEFAULT 'INHERIT',
    ADD COLUMN visible_in_ui         boolean,
    ADD COLUMN visible_in_pdf        boolean,
    ADD COLUMN show_when_zero        boolean,
    ADD COLUMN editable_input        varchar(20),
    ADD COLUMN allow_total_override  boolean,
    ADD COLUMN required_manual_input boolean,
    ADD COLUMN parameters_override   jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT chk_pacsr_calculation_mode
        CHECK (calculation_mode IN ('INHERIT','ZERO','MANUAL')),
    ADD CONSTRAINT chk_pacsr_excluded_is_inert
        CHECK (is_allowed = true OR calculation_mode IN ('INHERIT','ZERO'));
```

**`2026-08-15-02-commercial-scheme.sql`**
- insert scheme `COMMERCIAL` (`allows_performance_bonus = false`)
- backfill `employee_compensation_scheme_history` from `works_in_commercial`,
  `valid_from` = first day of the next month (D1)

**`2026-08-15-03-complete-scheme-category-matrix.sql`** (D6)
- **21 of the 26 current scheme × category pairs have no rule** (finding F8):
  `STANDARD` has none at all, `FOREIGN_FIXED_COEFFICIENT` has 5 denies. With
  `COMMERCIAL` added the matrix is 39 pairs. Every one gets an explicit row.
- insert a rule for **every** active scheme × active category combination that
  has none, reproducing today's effective behaviour exactly (today's default is
  ALLOW, so the generated rows are `is_allowed = true` plus the category's own
  defaults) — so the backfill changes nothing by accident
- explicit deny + invisible rows for the foreign scheme's meal/transport
- the `COMMERCIAL` / `MONTHLY_BONUS` `ZERO` rule
- final `DO $$` block that **raises** if any active scheme × active category
  combination is still missing a rule

### Phase 6 — no migrations

DTO + frontend only.

### Phase 7 — clean-up

**`2026-09-01-01-drop-legacy-payroll-columns.sql`** — only after **one full
verified payroll cycle** on the new model.
- drop meal / transport / bonus / telephone columns from `payroll_run_items`
- drop `hourly_rate`, `transport_allowance_rsd`, `transport_allowance_mode` from
  `employees`
- drop `override_target`, `allow_override` from `payroll_adjustment_categories`
- decide `total_gross_earnings`: compute it or drop it — it must not stay a
  column that always reads 0

**Kept** on `payroll_run_items`: `total_net_earnings`,
`total_deductions_amount`, `previously_paid_amount`, `current_balance_amount`,
`net_payable_amount`, `previous_net_payable_amount` — denormalised totals for
list screens.

### 1.1 Category configuration matrix (Phase 3 seed)

| code | `calculation_key` | `input_type` | `editable_input` | `allow_total_override` | `required_manual_input` |
|---|---|---|---|---|:-:|
| `MEAL_ALLOWANCE` | `MEAL_BY_ELIGIBLE_SHIFTS` | `QTY_X_RATE` | `UNIT_AMOUNT` | false | false |
| `TRANSPORT_ALLOWANCE` | `TRANSPORT_BY_QUALIFYING_SHIFTS` | `QTY_X_RATE` | `NONE` | **true** | false |
| `MONTHLY_BONUS` | `MONTHLY_BONUS_FROM_CATEGORIES` | `AMOUNT` | `CORRECTION` | **true** | false |
| `FIXED_SALARY` | `MANUAL` | `AMOUNT` | `AMOUNT` | false | false |
| `POSITIVE_NEGATIVE_CORRECTION` | `MANUAL` | `AMOUNT` | `AMOUNT` | false | false |
| `OTHER` | `MANUAL` | `AMOUNT` | `AMOUNT` | false | false |
| `INSTALLMENT` | `MANUAL` | `AMOUNT` | `AMOUNT` | false | false |
| `PHONE_CURRENT_MONTH` | `MANUAL` | `AMOUNT` | `AMOUNT` | false | false |
| `PHONE_PREVIOUS_MONTH` | `PHONE_FROM_PREVIOUS_MONTH` | `AMOUNT` | `AMOUNT` | false | false |
| `PAID_PART_1` / `PAID_PART_2` | `MANUAL` | `AMOUNT` | `AMOUNT` | false | false |
| `PAID_PREVIOUS_PERIOD` | `PAID_PREVIOUS_PERIOD` | `AMOUNT` | `NONE` | false | false |
| `PREVIOUS_BALANCE` | `PREVIOUS_BALANCE` | `AMOUNT` | `NONE` | false | false |

`section_code` is **not** in this table and must not be changed here: production
already differs from the seed (finding F1) and moving a category between sections
moves money. `TRANSPORT_BY_WORK_DAYS` is renamed to
`TRANSPORT_BY_QUALIFYING_SHIFTS` because D3 changes what it counts — the old name
would describe an algorithm the code no longer implements.

---

## 2. Java files

### Phase 0 — added ✅
```
src/test/java/.../support/PayrollScenarioFixture.java      new
src/test/java/.../PayrollGoldenSnapshotIT.java             new
docs/business-rules/payroll-migration-diagnostics.sql      new (read-only, run by hand)
```
`PayrollScenarioFixture` is a `@Component` under the scanned package, so it is
available to every integration test. It writes `monthly_reports` directly rather
than driving `DailyRecalcService` / `MonthlyRecalcService`: this migration does
not touch the recalculation pipeline, and driving it would make the snapshot fail
for unrelated reasons.

`PayrollScenarioFixture.CATALOGUE` **mirrors production, not the seed** — see
finding F1. Diagnostic Q12 dumps the real catalogue; if it ever changes,
that constant changes with it or the golden test pins fiction.

### Phase 1 — changed ✅
```
app_settings/AppSettingService.java
    + getMealAllowancePerDayOn(LocalDate) / getTransportAllowancePerDayOn(LocalDate)
payroll_run_item/PayrollRunItemService.java
    recalculateFromMonthlyReport   priced at mr.getStartDate(), not now()
                                   takes the scope as a parameter
    getForPayrollAccess            resolves the scope once, hands it to both steps
    getForPayrollRun               one batched scopesFor for the whole run
    refreshIfStale                 takes a ScopeSource
    reconcileItemCategories        takes the scope as a parameter
    + ScopeSource / scopeSourceFor batched for a run, per item otherwise
payroll_run/PayrollRunInitializationTxService.java
    buildPayrollRunItem            priced at mr.getStartDate(), not now()
```

**Phase 1 changes no existing amount and is safe to deploy as it stands.** Q18
shows `meal_allowance_per_day` and `transport_allowance_per_day` have each had
exactly one value since 2020-03-22, and Q18b reports `total_change = 0.00` for
every payroll period. The `now()` reads were a latent defect that had never fired
because no rate has ever moved; the fix closes it before the first rate change
does.

**No hard error was needed.** The plan anticipated a missing payroll period and a
warned fallback; in fact `monthly_reports.start_date` is `NOT NULL` and in scope
at every pricing site, so the period is always known and there is no fallback
path to get wrong. `PayrollRunItem.period` — which *is* nullable — is used only
to decide whether a run can be batched, and a null there degrades to per-item
resolution rather than to "unrestricted". `PayrollSchemeScopeBatchingIT`
covers that.

**The pricing rule now has an explicit boundary:** a rate is read at the
**first day of the payroll month**, so a rate saved mid-month applies from the
following month. Pinned by `aMidMonthRiseAppliesFromTheNextMonth`.

### Phase 2 — added ✅
```
employee_payroll_value/EmployeePayrollValueCodes.java
employee_payroll_value/EmployeePayrollValueDefinition.java
employee_payroll_value/EmployeePayrollValueDefinitionRepository.java
employee_payroll_value/EmployeePayrollValueHistory.java
employee_payroll_value/EmployeePayrollValueHistoryRepository.java   (batch lookup + row lock)
employee_payroll_value/EmployeePayrollValueService.java             (close + open, one tx)
employee_payroll_value/EmployeePayrollValueController.java
employee_payroll_value/dto/{EmployeePayrollValue,ChangeEmployeePayrollValueRequest}.java
src/test/java/.../EmployeePayrollValueIT.java                       (14 tests)
src/test/java/.../EmployeeCompensationSchemeChangeIT.java           (5 tests — F12)
```

Two implementation notes worth keeping:

- **`value_type` is bound by a COMPOSITE foreign key**
  `(value_definition_id, value_type) → employee_payroll_value_definitions(id, value_type)`.
  That makes "the populated column matches the declared type" a database
  guarantee rather than a trigger's job, and there is no way to write a numeric
  value against a `TEXT` definition. Covered by
  `valueTypeCannotDriftFromItsDefinition`.
- **The association owns the column.** An earlier draft mapped
  `value_definition_id` twice — a read-only `@ManyToOne` plus a writable `Long` —
  which left the association unresolvable after insert. One writable
  `@ManyToOne` is both simpler and correct.

### Phase 2 — changed ✅
```
payroll_run_item/PayrollRunItemService.java
    + hourlyRateFor(item, pricingDate)   value history first, employees.hourly_rate as fallback
    recalculateFromMonthlyReport         pricingDate hoisted to the top; every date-effective
                                         read in the method now uses it
employee_compensation_scheme_history/EmployeeCompensationSchemeService.java
    changeScheme                         saveAndFlush on the close (F12)
```

**`null` from `hourlyRateFor` means "not configured", not zero**, and the caller
leaves the existing system rate alone. 923 of 949 items calculate at rate 0 today
and must keep doing so; overwriting them with zero would be a change dressed up
as a refactor. Covered by `noRateAnywhereLeavesTheItemAlone`.

**`changeValue` accepts a date before the whole history** (OPEN-7). One rule
covers appending, filling a gap and prepending: the new period runs from
`effectiveFrom` until the day before the next period starts, or open-ended if
there is none, and the period covering `effectiveFrom` is closed the day before.
Backdating onto a successor that already holds the same value extends that period
instead of splitting it — two adjacent periods with the same number say nothing a
single one does not, and the split would misrepresent a correction as a change of
rate. Only a date that starts exactly where an existing period starts is refused,
because replacing a period in place is the one thing this service must never do.

### Phase 3 — added ✅
```
payroll_calculation/CalculationKeys.java
payroll_calculation/PayrollComponentCalculator.java
payroll_calculation/ComponentContext.java
payroll_calculation/ComponentResult.java
payroll_calculation/PayrollCalculatorRegistry.java
payroll_calculation/calculators/ManualCalculator.java
payroll_calculation/calculators/MealAllowanceCalculator.java
payroll_calculation/calculators/TransportAllowanceCalculator.java
daily_report/DailyReportRepository.java      + countQualifyingShifts(employeeId, from, to)
```

**Only three calculators, not seven — the rest would have been invented rules.**
The catalogue named `MONTHLY_BONUS_FROM_COMPONENTS`, `PAID_PREVIOUS_PERIOD` and
`PREVIOUS_BALANCE`, and Q7 confirms nothing has ever executed any of them: the
bonus is entered by hand and the other two are display mirrors reaching no total.
Writing an algorithm for them would be deciding a business rule, so migration
`2026-08-05-01` sets them to `MANUAL` — which is what the system genuinely does —
and the automatic rules stay recorded as OPEN-8, OPEN-9 and OPEN-10.

`TRANSPORT_BY_WORK_DAYS` is renamed to `TRANSPORT_BY_QUALIFYING_SHIFTS`, because
D3 changed what it counts and the old name would describe an algorithm the code no
longer implements.

**`ComponentContext` carries values, not services.** A calculator cannot reach for
`employee.isForeigner()`, cannot read a setting at `now()`, and cannot issue a
query of its own — everything is resolved for the payroll period before it is
called. That is also the shape Phase 4 needs to hoist the lookups out of the loop.

**`ComponentResult.zero(reason)` always carries a reason.** No rate configured, no
qualifying shift, excluded by the scheme — all legitimate, all produce zero, and
without the reason none can be told apart from a fault. It lands in
`payroll_adjustments.calculation_inputs`.

### Phase 3 — changed
```
payroll_adjustment_category/PayrollAdjustmentCategory.java  + editableInput, allowTotalOverride,
                                                              showWhenZero, requiredManualInput
payroll_adjustment/PayrollAdjustment.java                   + correctionAmount, overrideReason,
                                                              hasManualInput, status,
                                                              calculationInputs, calculatedAt
```

### Phase 4 — changed ✅
```
payroll_run_item/PayrollRunItemService.java
    recalculateSummaryTotals    earnings = categories + SUM(applied GROSS_PLUS adjustments)
                                the meal/transport direct adds and the by-code
                                exclusions are gone
    patch step 2                meal unit price now syncs the adjustment row (F11)
    patch step 7                FIXED_SALARY solved against GROSS_PLUS, no separate
                                meal subtraction — it would now be taken off twice
    updateAdjustmentByCategoryCode
                                takes an explicit humanOverride flag
payroll_adjustment/PayrollAdjustmentService.java
    create                      refuses a second row of the same category (D9)
```

**`updateAdjustmentByCategoryCode` no longer infers `is_overridden`.** It used to
set it whenever the amount differed from `system_amount`, which was wrong in the
ordinary case — a recalculation writes the system's own figure and was marking the
line as human-overridden — and could not tell a repriced meal (an INPUT edit, with
the formula still running) from a typed-in total (the formula bypassed). The
caller now says which, because the value alone cannot.

**Only the EARNINGS side moved to impact codes.** `GROSS_PLUS` is exactly the set
the old code reached by adding meal and transport from the item columns and then
summing `ADDITIONS` minus those two, so the money is provably identical.
Settlements stays on `section_code` — see OPEN-12.

**`chk_pa_override_reason` is deferred to Phase 6.** D7 requires a reason on every
hard override and the column exists, but the patch request has no field to carry
one: enforcing it now would reject ordinary edits that no UI could satisfy. The
constraint ships with the request field that feeds it.

### Phase 5 — added
```
payroll_configuration/PayrollConfigurationValidationService.java
payroll_configuration/PayrollConfigurationProblem.java
payroll_configuration/PayrollConfigurationController.java   (admin read-only report)
compensation_scheme/CompensationSchemeActivationService.java (D6 lifecycle)
```

### Phase 5 — changed
```
work_category_resolution/PayrollSchemeScope.java
    Set<Long> allowedAdjustmentCategoryIds -> Map<Long, EffectiveComponentConfig>
work_category_resolution/PayrollSchemeScopeService.java
    exactly-one-scheme resolution; union() deleted; 0 or >1 -> exception
payroll_adjustment_category/PayrollAdjustmentCategorySchemeRule.java   + 8 fields
employee_compensation_scheme_history/EmployeeCompensationSchemeService.java
    changeScheme: normalise effectiveFrom to the first day of the next month;
    explicit "replace scheduled change" operation
compensation_scheme/CompensationSchemeCodes.java   + COMMERCIAL
payroll_adjustment_category/PayrollAdjustmentCategoryService.java  activation gate (D6)
```

### Phase 6 — changed ✅ (backend)
```
payroll_run_item/dto/PayrollAdjustmentDetailDto.java
    + visibleInUi / visibleInPdf / showWhenZero / editableInput /
      allowTotalOverride / requiredManualInput / calculationMode  — all from the
      EFFECTIVE config, not the raw category
    + correctionAmount / overrideReason / hasManualInput / status / calculationInputs
payroll_run_item/dto/AdjustmentPatchDto.java
    + correctionAmount / overrideReason / clearOverride
payroll_run_item/dto/PayrollRunItemPatchRequest.java
    + setAdjustments / setCurrentMonthTelephone
payroll_run_item/PayrollRunItemService.java
    getDetails                 每 line carries its scheme config
    + applyAdjustmentPatch     the edit policy, enforced
```

**The edit policy is now enforced by the SERVER.** Until this phase
`allow_override` was decoration — it said FALSE on meal and transport while both
were edited daily, because the patch went through the item columns where nothing
read it. A rule only the client honours is a rule anybody with the API can ignore.
`applyAdjustmentPatch` refuses an input the scheme does not permit, refuses a
total override where `allowTotalOverride` is false, refuses one without a reason,
and refuses every edit on a line the scheme forces to zero.

**`chk_pa_override_reason` shipped** in `2026-08-25-01`, `NOT VALID`: rows
overridden before today have no reason, and back-filling one would put words in
somebody's mouth.

### Phase 6 — frontend ✅
```
payrolls/domain/adjustmentPolicy.ts        NEW — the whole decision, as pure functions
payrolls/domain/adjustmentPolicy.test.ts   NEW — 14 tests
payrolls/types/employeePayroll.types.ts    + the effective-config fields
payrolls/api/payrollRunItems.api.ts        + correctionAmount / overrideReason / clearOverride
payrolls/ui/PayrollAdjustmentsSection.tsx  renders from the policy; explains a zero
payrolls/ui/PayrollCategoriesTable.tsx     ditto; the meal block follows its line
payrolls/ui/PayrollPdf.tsx                 filters on visibleInPdf + showWhenZero
payrolls/ui/EmployeePayroll.tsx            asks for a reason before a total override
payrolls/ui/employeePayrollRoute.test.tsx  + 3 route tests
```

**All the deciding is in one pure module.** `resolveAdjustmentPolicy`,
`shouldRenderAdjustment`, `isTotalOverride` and `explainZero` are plain functions
over one line's fields; the components call them and decide nothing. That is what
makes "add a seasonal worker type" a data change: there is no branch anywhere in
the feature that asks what kind of employee this is.

**The old flags are kept as a fallback**, marked `@deprecated`. A client running
against an older API still renders — inaccurately, because `isManual` and
`allowOverride` never could express "the system counts the meals and a person may
reprice one", which is why they were replaced — rather than showing every line
read-only.

**A zero now explains itself.** `calculation_inputs.reason` becomes "Nema
odrađenih dana", "Ispod minimalnog broja sati" and so on, next to the amount.

**The reason for an override is asked for before the patch is sent**, not after
the server rejects it. Cancelling leaves the edits pending, so nothing typed is
lost.

**One gap this exposed:** the meal block in `PayrollCategoriesTable` is hardcoded
and reads `summary.mealAllowanceCount` / `totalMealAllowanceAmount` — the same
double bookkeeping phase 4 removed on the server, still present in the UI. It now
renders only when the `MEAL_ALLOWANCE` line says it should, so a foreign worker's
payslip loses the block instead of showing an unexplained 0,00 beside a label.
Phase 7 replaces it with the line itself when the columns are dropped.

### Phase 7 — changed
```
payroll_run_item/PayrollRunItem.java     legacy columns removed
employee/Employee.java                   hourlyRate / transportAllowance* removed
employee/dto/*                           corresponding fields removed
employee/EmployeeService.java            :271-278 transport mode branch removed
employee/EmployeeFieldMapper.java        :35 transportAllowanceRsd sort key removed
```

---

## 3. Frontend files (Phase 6)

```
src/ui/features/payrolls/types/employeePayroll.types.ts    generic PayrollLine
src/ui/features/payrolls/api/payrollRunItems.api.ts        new patch shape
src/ui/features/payrolls/hooks/useUpdatePayrollRunItem.ts
src/ui/features/payrolls/ui/PayrollParametersPanel/PayrollParametersPanel.tsx
src/ui/features/payrolls/ui/PayrollAdjustmentsSection.tsx
src/ui/features/payrolls/ui/PayrollSummarySection.tsx
src/ui/features/payrolls/ui/PayrollPdf.tsx                 filter visibleInPdf + showWhenZero
src/ui/features/payrolls/ui/EditableAmountCell.tsx         render by editableInput
src/ui/features/payrolls/ui/employeePayrollRoute.test.tsx
```

Rendering is driven only by `visibleInUi`, `visibleInPdf`, `showWhenZero`,
`editableInput`, `allowTotalOverride`, `requiredManualInput`, `calculationMode`.

`src/ui/features/employees/domain/employeeIdentity.ts` keeps `foreigner` /
`worksInCommercial` — it is a **presentational badge**, not payroll logic, and is
explicitly out of scope.

---

## 4. Tests by phase

### Phase 0 — golden snapshot (`PayrollGoldenSnapshotIT`) ✅ 15 green, 5 disabled
1. standard production employee — full arithmetic
1b. **section routing** — every line reaches exactly the total its section sends
    it to, with distinguishable non-zero amounts (added after finding F1)
2. foreign employee (no meal, no transport, no bonus, no payslip line)
3. commercial employee, bonus visible as 0
4. seasonal / invented scheme calculates with no code change
5. employee with an override · 5b. the override survives recalculation
6. remapped work-code category gets no payroll row
7. transport: one qualifying shift
8. transport: two qualifying shifts on the same day
9. transport: shift with zero work minutes · 9b absence-only · 9c across midnight
   · 9d one row per shift is DB-enforced · 9e outside the period
10. required manual input — `@Disabled("phase 3")`
11. optional manual input — `@Disabled("phase 3")`
12. period-correct price — `@Disabled("phase 1")`
13. scheme change effective the first of next month — `@Disabled("phase 5")`
13b. two schemes in one month is an error — `@Disabled("phase 5")`

Tests assert **today's** behaviour where it exists and are
`@Disabled("enabled in phase N")` where it does not, so the list is visible in
the suite rather than remembered.

### Phase 1 ✅
`PayrollGoldenSnapshotIT`
- 12 old month recalculated after a price rise keeps the old price
- 12b a later month gets the new price — the mirror, so "freeze everything" fails
- 12c a rate starting mid-month does not reprice that month
- 12d an override survives, and `*_system` still tracks the period

`PayrollSchemeScopeBatchingIT` (`@MockitoSpyBean` on `PayrollSchemeScopeService`)
- a five-employee run calls `scopesFor` **once** and `scopeFor` **never**
- two employees on different schemes in one batch keep their different answers
- an item with no period falls back to per-item resolution — the dangerous
  failure would be an empty batch read as "unrestricted", paying an excluded
  meal allowance

### Phase 2 ✅
`EmployeePayrollValueIT` — close + open in one transaction · an old date still
resolves to the old value · before the first period there is no value (not zero)
· a future period blocks an earlier change · the same value is refused · an
unregistered code is refused · overlap rejected by the constraint · touching
periods allowed (the inclusive-`valid_until` proof) · inverted period rejected ·
value column must match the declared type · `value_type` cannot drift from its
definition · batch lookup

`EmployeeCompensationSchemeChangeIT` — F12: close-then-open, history appended
never rewritten, two changes in sequence, same scheme refused, future period
blocks an earlier change

`PayrollGoldenSnapshotIT` 14/14b/14c/14d/14e — the rate comes from the history and
is priced at the period · a later month gets the later rate · with no history the
employee column is still used · no rate anywhere leaves the item alone · an
override outranks the history while `*_system` keeps tracking it

Still owed, and listed so it is not forgotten: **a test that runs the Phase 2
backfill SQL against seeded payroll items and asserts the collapse produced the
right period boundaries.** The migration carries its own `DO $$` verification
block, which fails the migration if any item stops resolving to its own system
rate, but that is not the same as a test.

### Phase 3 ✅
`PayrollGoldenSnapshotIT` 15–15f
- an employee with a rate and qualifying shifts is finally paid transport, and the
  zero-work shift is excluded
- no rate → 0 with `reason = NO_RATE_CONFIGURED` on the adjustment
- a rate starting later leaves earlier months untouched — the R9 control, proved
- the foreign scheme beats the rate: excluded is still 0
- every `calculation_key` in the catalogue has a calculator
- an unknown key throws instead of paying zero

Changed expectations, deliberately, both in test 1 and test 12:
`transport_allowance_unit_amount` 350,00 → 0,00. It used to display the global
`app_settings` rate against a count that was never computed; it now shows the
employee's own rate, and the fixture employee has none.

Still owed: `required_manual_input` behaviour (columns and status values exist,
the lock-time validation does not), and the backfill-SQL test from Phase 2.

### Phase 4 ✅
- 16 the legacy columns still mirror the adjustment rows exactly — this IS the
  dual-write check, run in a test rather than over a production month
- 16b meal counted exactly once: 79 920,00. Double-counting gives 85 920,00 and
  dropping the row gives 73 920,00 — both one number away and invisible otherwise
- 16c a second row of the same category is refused
- 5c rewritten: patching the meal price now moves the adjustment row immediately,
  and doing so is NOT a hard override
- `sectionCodeRoutesMoneyNotImpactCode` unchanged and still green — 102 420,00 /
  52 700,00 / 23 500,00 / 49 720,00 — which is the proof the switch moved no money

~~Still owed: audit reconstruction test~~ — `PayrollAuditReconstructionIT`, once
the reason fields existed (Phase 6 for lines, 2026-08-27-01 for time). It asks
the business question rather than naming a column, so it survives a decision
moving tables — which three of them since have.

It also PINS A DEFECT it found rather than asserting it away:
`trg_audit_logs_payroll_adjustments` has no WHEN clause, so every recalculation
writes a full-row diff per line. 20 954 of 33 472 update entries in the
development database touch nothing but `system_*`, `calculated_at` and
`calculation_inputs`, against roughly thirty real decisions — and a read of a
stale item is a write, so the count grows whenever anybody opens a payroll. A
WHEN clause cannot fix it, for the reason 2026-09-03-01 gives about activity: a
patch and the recalculation it triggers land in the same UPDATE, so a clause
narrow enough to drop the churn drops the decision with it. The fix is the one
that worked for activity — record decisions at the caller and stop auditing the
row — and it is not done.

### Phase 5 ✅
`PayrollSchemeScopeIT` — rewritten, as the plan required rather than deleted:
- `midMonthChangeUnionsBothSchemes` → `twoSchemesInOneMonthIsAnError`
- `noSchemeYieldsNoScope` → `noSchemeIsAnError`
- `adjustmentCategoriesAreOpenByDefault` → `adjustmentCategoryWithoutARuleIsAnError`

`EmployeeCompensationSchemeChangeIT` — a mid-month date is refused rather than
snapped forward · the current month is too late · the first of next month is
accepted and no month spans two schemes · the FIRST assignment is exempt · a
scheduled change can be replaced through a named operation, and the superseded
period is archived rather than erased

`PayrollSchemeScopeBatchingIT` — an employee with no scheme stops the whole run
rather than being paid unrestricted

Still owed: `PayrollConfigurationValidationService` and the category/scheme
activation gates. The migration raises on an incomplete matrix and the resolver
throws at calculation time, so the rule is enforced; what is missing is the
admin-facing report that finds the gap before somebody runs payroll.

### Phase 6 ✅ (backend)
`PayrollGoldenSnapshotIT` 18–18g
- a total override is refused where the scheme does not allow one
- a total override without a reason is refused
- with the reason it applies, and the system figure survives beside it
- editing a permitted input is NOT an override and needs no reason
- a forced-zero line refuses every edit, by any client
- clearing an override returns the line to the system figure
- the response carries `visibleInUi` / `showWhenZero` / `calculationMode` /
  `editableInput` / `allowTotalOverride`, so the client can render a commercial
  bonus correctly without knowing what "commercial" is

Frontend tests still to come.

---

## 5. Risks and rollback points

| # | Risk | Phase | Mitigation | Rollback |
|---|---|:-:|---|---|
| R1 | Transport starts paying money it never paid (today `transport_allowance_days` is always 0) | 3 | explicit business sign-off before deploy; golden test records before/after | set the `TRANSPORT_ALLOWANCE` rule to `calculation_mode = ZERO` — data-only |
| R2 | Totals shift when meal/transport stop being added directly | 4 | dual-write + comparison query over a full month before the switch | revert the Phase 4 Java commit; the schema is unchanged |
| R3 | `is_overridden` semantics change misclassifies existing overrides | 4c | migration only touches rows where solely unit/quantity differ; counted and logged | column values restorable from `audit_logs` |
| R4 | The `UNIQUE` in D9 fails on production duplicates | 4 | diagnostic report first; migration refuses to run if duplicates remain | do not add the constraint; keep the service-level guard |
| R5 | Exactly-one-scheme rule breaks employees with no period | 5 | Phase 0 diagnostic counts them; backfill before the rule ships | feature-flag the strict resolution for one release |
| R6 | The complete-matrix requirement blocks routine admin work | 5 | backfill generates today's behaviour for every pair; activation gate only applies to *new* categories/schemes | validation service reports instead of throwing |
| R7 | Frontend and backend drift during Phase 6 | 6 | old named DTO fields kept in parallel until the FE is migrated | FE is a separate deploy |
| R8 | Legacy columns dropped too early | 7 | at least one full verified payroll cycle on the new model | restore from backup — this is the only irreversible step |
| **R9** | **All 949 payroll items back to 2023-01 are `DRAFT`, none `LOCKED`.** `getForPayrollAccess` recalculates any non-`LOCKED` item on read, so every historical month silently adopts a new calculation the next time somebody opens it. 2023: 135 items · 2024: 405 · 2026: 409. | 3 | **Locking was rejected (OPEN-6) and there is no global cut-over month (OPEN-7), so the control is the employee's own `TRANSPORT_RATE.valid_from`.** The Phase 2 backfill starts every migrated rate at the first month **not yet calculated** — derived from `max(payroll_run_items.period) + 1 month`, not chosen — so no month already on a payslip can acquire a transport amount. Where transport genuinely started earlier, that one employee is backdated through `EmployeePayrollValueService.changeValue`, which accepts a date before the whole history. Per employee, auditable, and reversible by archiving the row. Phase 1 needed no mitigation at all: Q18 shows both rates have exactly one period ever and Q18b reports `total_change = 0.00` for every month. | archive the employee's `TRANSPORT_RATE` row — no rate in force means 0, which OPEN-7 confirms is a correct outcome |

**Rollback points** (each phase is a separate commit; the tree is deployable at
every one):

```text
after 0   nothing changed, only tests added
after 1   Java-only, revertable
after 2   additive schema; new tables unused by the calculation
after 3   additive schema; calculators exist but legacy path still writes
after 4   ← LAST FULLY REVERSIBLE POINT (schema still carries legacy columns)
after 5   configuration is authoritative; revert needs the Phase 5 migrations reversed
after 7   irreversible without a restore
```

---

## 6. Inventory of hardcoded logic

Everything that must eventually disappear, or be justified as staying.

### 6.1 Meal allowance

| Location | What | Removed in |
|---|---|:-:|
| `PayrollRunItemService.java:357-370` | patch step 2: unit price → total | 4 |
| `PayrollRunItemService.java:487` | meal added directly in the `totalNetEarnings` branch | 4 |
| `PayrollRunItemService.java:875-896` | count, rate, total, adjustment sync | 4 |
| `PayrollRunItemService.java:882` | `allowsAdjustmentCode(scope, "MEAL_ALLOWANCE")` | 5 |
| `PayrollRunItemService.java:1021,1026` | excluded from `additionsSum`, added directly | 4 |
| `PayrollRunInitializationTxService.java:262,299-303` | initial meal columns | 4 |
| `PayrollRunItem.java` | 5 meal columns | 7 |
| `PayrollRunItemPatchRequest.java`, `PayrollRunItemResponse.java` | named DTO fields | 6/7 |
| `AppSettingService.java:27,39-42` | `meal_allowance_per_day` key | stays — fallback inside the calculator |
| `DailyRecalcService.java:968-970`, `MonthlyRecalcService.java:257` | `meals_count` / `meal_allowance_num` | **stays** — this is the source datum, not payroll policy |

### 6.2 Transport allowance

| Location | What | Removed in |
|---|---|:-:|
| `PayrollRunItemService.java:327` | `CAT_CODE_TRANSPORT` | 4 |
| `PayrollRunItemService.java:373-383` | patch step 3 | 4 |
| `PayrollRunItemService.java:898-914` | rate × days, adjustment sync | 4 |
| `PayrollRunItemService.java:1022,1027` | excluded from `additionsSum`, added directly | 4 |
| `PayrollRunInitializationTxService.java:263,305-309` | initial transport columns | 4 |
| `Employee.java:84` `transportAllowanceRsd` | per-employee amount, **never read by payroll today** | 7 (moves to Phase 2 table) |
| `Employee.java` `transportAllowanceMode`, `EmployeeService.java:271-278` | mode, **never read by payroll today** | 7 |
| `EmployeeFieldMapper.java:35` | sort key | 7 |
| `AppSettingService.java:28,44-47` | `transport_allowance_per_day` key | stays — fallback |

### 6.3 Bonus

| Location | What | Removed in |
|---|---|:-:|
| `PayrollRunItemService.java:328` | `CAT_CODE_BONUS` | 4 |
| `PayrollRunItemService.java:386-431` | base / correction / total branches | 4 |
| `PayrollRunItem.java` | 9 bonus columns | 7 |
| `PayrollRunInitializationTxService.java:311-319` | initial bonus columns | 4 |
| `PayrollRunItemService.java:651-659,690-696` | `bonus_amount` per category from `scope.allowsPerformanceBonus()` | **stays** — scheme-driven already, D11-compliant |

### 6.4 Other lines still keyed by code

| Location | What | Removed in |
|---|---|:-:|
| `PayrollRunItemService.java:329,476-500` | `FIXED_SALARY` computed as net − meal − additions | 4 |
| `PayrollRunItemService.java:963-973` | `PHONE_PREVIOUS_MONTH` propagation | 4 |
| `PayrollRunInitializationTxService.java:38,221-232` | `PHONE_PREVIOUS_MONTH` from the previous month | 4 |
| `PayrollRunItemService.java:330-331,1019,1047,491` | `SECTION_ADDITIONS` / `SECTION_SETTLEMENTS` drive the arithmetic, not `impact_code` | 4 — needs an explicit sign convention decision |

**Finding F1 in detail — where each production section actually lands today:**

| `section_code` | categories | reaches |
|---|---|---|
| `ADDITIONS` | `TRANSPORT_ALLOWANCE`*, `FIXED_SALARY`, `MONTHLY_BONUS`, `POSITIVE_NEGATIVE_CORRECTION`, `OTHER` | `additionsSum` → `totalNetEarnings` |
| `SETTLEMENTS` | `INSTALLMENT`, `PHONE_PREVIOUS_MONTH`, `PAID_PART_1`, `PAID_PART_2` | `previouslyPaid` → `currentBalance` |
| `MEAL` | `MEAL_ALLOWANCE` | **no total** — the item column carries the money |
| `PHONE` | `PHONE_CURRENT_MONTH` | **no total** — deducted next month as `PHONE_PREVIOUS_MONTH` |
| `SETTLEMENTS_SUM` | `PAID_PREVIOUS_PERIOD` | **no total** — display mirror |
| `BALANCE` | `PREVIOUS_BALANCE` | **no total** — `previous_net_payable_amount` carries it |

\* excluded from `additionsSum` by code and added from the item column instead.

Separately, `impact_code = DEDUCTION_MINUS` feeds `totalDeductionsAmount`, which
is display-only and consumed by no other total — so `PHONE_CURRENT_MONTH` is
counted there while reaching no balance.

Phase 4 replaces the section filter with impact codes. **Any impact-driven sum
must keep `SETTLEMENTS_SUM`, `BALANCE`, `PHONE` and `MEAL` out of the balances**,
or three display mirrors start being charged twice.
`PayrollGoldenSnapshotIT.sectionCodeRoutesMoneyNotImpactCode` is the test that
catches it.
| `PayrollAdjustmentRepository.java:35` | `findByItemIdAndCalculationKey`, currently unused | 3 (used) |

### 6.5 `is_foreigner`

**No calculator reads it.** Remaining uses are legitimate:

| Location | Why it stays |
|---|---|
| `Employee.java:59` | personnel attribute, original meaning |
| `EmployeeMapper.java:24`, `EmployeeDetailDto.java:92`, `EmployeeService.java:218` | employee DTO |
| `WorkShiftRepository.java:104,119`, `EmployeeRecordRepository.java:56,78` | display projections for shift/record screens |
| `2026-07-27-02-...sql` backfill | historical, already executed |
| `PayrollRunItemService.java:312`, `WorkCategoryResolutionService.java:33`, `CompensationSchemeInitializer.java:21` | comments stating it must **not** be used — keep |

### 6.6 `works_in_commercial`

**Not used in payroll at all today.** It is a personnel flag plus display.

| Location | Fate |
|---|---|
| `Employee.java:99-100,170-171` | stays as personnel data |
| `employee/dto/*`, `EmployeeRepository*`, `EmployeeWithBonusView.java` | stays |
| `marel-app/src/ui/features/employees/domain/employeeIdentity.ts` | stays — badge/tone only |
| **new** in Phase 5 | becomes the source for the one-time `COMMERCIAL` scheme backfill, then stops being authoritative for payroll |

### 6.7 Behaviour that contradicts D1/D5 and must be rewritten

| Location | Current | Required |
|---|---|---|
| `PayrollSchemeScopeService.java:91-100` | merges every scheme overlapping the month | exactly one scheme, else error |
| `PayrollSchemeScopeService.java:195-202` | `union(...)` | delete |
| `PayrollSchemeScopeService.java:53-57` (javadoc) | "employee with no scheme … callers treat as no restriction" | error |
| `PayrollSchemeScopeService.java:118-121`, `PayrollRunItemService.java:251,260` | `scope == null` → unrestricted | error |
| `PayrollRunInitializationTxService.java:118-124` (javadoc) | "absent from the map is unrestricted" | error |
| `PayrollSchemeScopeIT:287-321` | tests asserting the union and the null scope | rewrite to the new rules |
| `compensation-schemes-and-category-localization.md:343-349` | documents ALLOW-by-default | update to D6 |


## Phase 7 preparation — step 1: the hourly rate has one writer ✅

`employees.hourly_rate` had stopped being what prices a payroll item, but nothing
had told `EmployeeService`. It wrote the column and rewrote every unlocked
payroll item directly, while `PayrollRunItemService.hourlyRateFor` resolved
HOURLY_RATE from `employee_payroll_value_history` and fell back to the column
only for an employee with no history at all. Two writers, and the reader
preferred the one the employee screen never touched: for anyone with a history
row the change applied, looked right, and was reverted by the next
recalculation. In the dev database that was 5 employees, against 4 who still had
the column set — already not the same set.

**Both write paths now record the rate in the history.** `patchEmployee` (the
detail screen) and `updateEmployee` (the edit form) both call
`recordHourlyRate`, so neither can diverge from the other.

**Default effective date: the first of the current month.** Not today. Payroll
prices a month at its START date, so a rate recorded mid-month would not be in
force for the month being calculated and the correction would appear to do
nothing until the next one. `EmployeePatchRequest.hourlyRateEffectiveFrom` is
optional and overrides it — that is how "this was actually their rate from
January 2025" is recorded.

**`EmployeePayrollValueService.setValue`** is new beside `changeValue`.
`changeValue` is the deliberate "add a period" operation and refuses a date that
is already taken; that is right for the value-history API and wrong for an
employee screen, where saving twice in a month is a typo being fixed. `setValue`
corrects the period starting on that exact date in place. Two periods starting
the same month would assert a raise that never happened, and the overwritten
value stays in `audit_logs`.

**The retroactive rewrite is gone.** `updateHourlyRateByEmployeeId` repriced
every open month with a rate that was never in force for most of them — the
defect the history table exists to close. `markNeedsRecalculationByEmployeeId`
replaces it: each item re-resolves the rate for ITS OWN month, so an earlier
month keeps the earlier rate, and items the old code had already overwritten are
repaired on the way through. Both `updateHourlyRateByEmployeeId` methods are kept
but `@Deprecated` with the reason, rather than deleted.

Covered by `EmployeeHourlyRateHistoryIT` (8 tests). Suite: **185 integration
tests**, up from 177.

**Still not droppable.** `employees.hourly_rate` remains the fallback for the
employees who have no history at all, and is still dual-written. Step 1 removes
the divergence, not the column.


## Phase 7 preparation — the human inputs on the item are audited ✅

`2026-08-05-02` left `payroll_run_items` unaudited and justified it with "the
amounts on an item are all derived from the adjustments and categories that ARE
audited". True of the money. **False of `manual_adjusted_minutes` and
`hourly_rate_overridden`** — an administrator types those, they change what an
employee is paid, and they exist on no other table. Adding 60 minutes to
somebody's month left no record of who did it or when.

`2026-08-26-01` attaches a trigger for exactly those two columns. The original
churn objection still holds and is why this is not row-level auditing:
`getForPayrollAccess` rewrites an item on every lazy recalculation, so a
row-level audit would bury the decisions in system writes.

**A `WHEN` clause, not `AFTER UPDATE OF`.** `UPDATE OF` fires when a column
appears in the statement's target list, and Hibernate names every column on every
save — so it would fire on each recalculation and reintroduce the churn. `WHEN`
compares the values themselves.

`audit_tables` now contains a `payroll_run_items` row, which is required for
`audit_trigger_fn` to resolve the table id. **It must not be read as full
coverage** — the trigger comment says so, because someone scanning `audit_tables`
would otherwise assume the whole row is tracked.

Recorded entries carry the whole row diff, not just the two columns: the WHEN
clause decides *whether* to log, `audit_trigger_fn` then diffs everything. That is
deliberate — the amounts that moved because of the decision are the interesting
part.

Past decisions cannot be reconstructed: 1 item already carries manual minutes and
6 an overridden rate, with no trail. The migration says so rather than inventing
one.

Covered by `PayrollItemHumanInputAuditIT` (4 tests), including the negative case —
a recalculation rewrites most of the row and must produce **no** entry. Suite:
**189 integration tests**.


## Time corrections get their own table ✅

`payroll_run_items.manual_adjusted_minutes` was one signed integer. It said how
many minutes were added and nothing else — not why, not by whom — and it could
not hold two corrections with different causes in the same month, so fixing one
of them meant recomputing the other by hand first.

`2026-08-27-01` adds `payroll_time_adjustment_categories` and
`payroll_time_adjustments`, shaped like the money pair.

**Not a row in `payroll_adjustments`, deliberately.** Every impact code there
moves money into a total — GROSS_PLUS, DEDUCTION_MINUS, PAYMENT_MINUS,
BALANCE_PLUS. A minutes row would either be summed into somebody's pay or need a
code that every sum-by-impact has to remember to skip. Money and time also round,
validate and get entered differently. Both are manual corrections; they are not
the same kind of value.

**Zero is not stored.** `chk_pta_minutes_nonzero` rejects it and setting a
correction to 0 archives the row. Absence of a row is how "nothing was corrected"
is said, so this table needs no equivalent of the money side's show-when-zero
problem.

**A reason is compulsory**, enforced against the category's `require_reason`
rather than unconditionally, so a future automatic correction is not made to
invent an explanation for arithmetic. A trigger does it, because a CHECK cannot
reach another table. The service raises it as a `ConflictException` first so the
user sees a sentence instead of a SQL error, and the frontend asks for the reason
*before* sending — cancelling leaves the edits pending.

Re-saving an unchanged correction does **not** demand the reason again: a form
that resubmits every field must not fail because nobody retyped an explanation
for a value they did not touch.

**Only `MANUAL_CORRECTION` is seeded.** The list floated in design discussion —
`MISSING_SHIFT`, `PAID_ABSENCE_CORRECTION`, `OVERTIME_CORRECTION` — is not:
nobody stated those as business rules, and three of them would duplicate records
the system already keeps at the source (`work_shifts`, and `monthly_reports`
together with `work_code_categories.type`, which is the OPEN-16 rule). A
correction here must never stand in for fixing the underlying record, or payroll
and the norm/efficiency reports end up disagreeing about the same month.

**Not mirrored, on purpose:** no scheme-rules table (D6 exists because money
lines need a per-scheme answer; nobody has said time corrections vary by scheme)
and no translations table yet. Both can be added without touching these rows.

`manual_adjusted_minutes` stays dual-written until phase 7 drops it, exactly as
the meal and transport columns are. The recalculation now sums the rows.

One defect caught by the tests: `@Builder` bypasses field initialisers, so
`builder()` produced `null` for `has_manual_input` and friends until
`@Builder.Default` was added — it would have failed at the first insert in
production.

Covered by `PayrollTimeAdjustmentIT` (10 tests). Suite: **199 integration
tests**, up from 189. Frontend: 914 green.


## "I, II i III smena" stops appearing for standard employees ✅

Work category **S** (id 28) is the TARGET of the fixed-coefficient remap: under
FOREIGN_FIXED_COEFFICIENT the eight real shift categories all calculate as S, and
it is the one that earns a payslip row there.

STANDARD had no rule for it, and `allow_unmapped_categories` is TRUE for STANDARD
— the deliberate choice that an unlisted category is allowed rather than silently
dropped. So S was allowed there too and every standard employee got a permanent
empty row for a category they never book against; they book on J and D.

**This is the mirror of a case already handled.** `PayrollSchemeScopeService`
drops a SOURCE that remaps elsewhere, so J does not appear for a foreign
employee. Nothing said the reverse — that the TARGET has no business appearing
for a scheme that never remaps into it.

`2026-08-28-01` records an explicit deny rule. Configuration, not code: which
categories a scheme pays is data by design, and code would have had to guess that
S is special. It is not special, it is simply not STANDARD's.

Verified before applying: the three standard rows on S (items 2514, 2579, 2688)
all hold **zero** minutes. The only row with real minutes — 360, item 2715 —
belongs to an employee on FOREIGN_FIXED_COEFFICIENT, where S belongs. The
migration carries a guard that refuses to run if any standard item has minutes
there, because the premise would then be wrong.

Nothing is deleted. `getDetails` filters on "allowed by the scheme OR has
activity", so the rows stop displaying while staying recoverable — and recorded
work always wins over a display rule.

COMMERCIAL is deliberately left alone: same shape, no employees yet, and nobody
has said what it pays.

**A trap caught on the way:** the first version raised an exception when category
S was absent. Work categories are data, not schema — the integration tests build
their database from these very scripts and have none of them — so that would have
failed the schema build for every environment that is not this factory. It is a
NOTICE and a return now.
