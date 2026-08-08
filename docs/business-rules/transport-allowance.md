# Transport allowance

How transport is paid, who is entitled to it, and what counts as one fare.

> **Status: implemented; migration APPLIED to `marel_app` on 2026-08-05 and every
> payroll item recalculated** — 97 entitlements moved back, 3 created, sweep
> visited 849 items, 849 recalculated, 0 failed. See §5.
>
> `2026-09-14-01` then closed the gap for the 37 employees whose fixed monthly
> amount begins 2026-08-01 — 37 closed periods created, payroll swept again,
> 849/849, 0 failed.
>
> **One employee is still open**: Iva Đurđević (id 6) has no entitlement before
> 2026-08-07. See §7.

---

## 1. Two modes, never both

| Mode | What decides it | What is paid |
|---|---|---|
| **Fixed monthly** | the employee has `TRANSPORT_FIXED_MONTHLY` in force | the whole monthly amount, whatever they worked |
| **Per arrival** | the employee has `TRANSPORT_PER_DAY` in force and TRUE | one company rate per journey to work |
| **Neither** | no value in force | **nothing**, with the reason `NO_TRANSPORT_ENTITLEMENT` |

Having the value **is** the mode. There is no separate flag that could fall out
of step with it. Where an employee somehow carries both, the fixed amount wins,
so a data mistake cannot pay somebody twice.

The two are modes, not two rates for the same thing, which is why neither is a
fallback for the other: a fixed employee is not paid more for coming in more
often, and a per-arrival employee has no monthly figure to fall back to.

The rate for the per-arrival mode is the single company setting
`app_settings.transport_allowance_per_day`, read at the **last day** of the
payroll month. The per-employee value says WHO and FROM WHEN, never HOW MUCH.

---

## 2. An arrival is not a day and not a shift

**This is the rule, and all three numbers differ.**

```
first shift, straight into the second    1 day   2 shifts   1 ARRIVAL
first shift, home, then the third        1 day   2 shifts   2 ARRIVALS
night shift, then the morning shift      2 days  2 shifts   1 ARRIVAL
```

- **Per day underpays.** Somebody who works the first shift, goes home, and comes
  back for the third has travelled twice on one day.
- **Per shift overpays** — and is what the system did until 2026-09-13. Nobody
  goes anywhere at a shift changeover.
- **Per arrival** is what is actually reimbursed.

### The threshold

A shift begins a **new arrival** when it starts **more than
`TransportAllowanceCalculator.ARRIVAL_GAP_MINUTES` (60) minutes** after the
previous one ended. Consecutive and overlapping shifts chain into one arrival —
a zero or negative gap is never greater than the threshold.

Sixty minutes rather than exact adjacency because a shift entered with the clock
rounded a little should not become a second fare. It is a constant rather than a
setting because it is one company-wide rule and nobody has asked to vary it;
moving it to `app_settings` is a one-line change, since the query already takes
it as a parameter.

### Ordered across the month, not within a day

`countQualifyingArrivals` orders every qualifying shift in the period and
compares each with the one before it — **it does not group by `work_date`
first**. That is what makes a night shift ending at 06:00 and a morning shift
starting at 06:00 the **next calendar day** one arrival. Grouping by day would
split that pair and pay twice.

### What does not count

A shift with `total_work_minutes = 0` is not a journey, and does not link the
shifts on either side of it into one chain either. Work minutes count only
categories of type WORK — absence and sick leave are excluded by
`DailyRecalcService.fillDailyTotals` — and are not the planned shift duration.

### Known edge, deliberately left

A chain that spans the month boundary is counted once in each month, because each
month is queried on its own. Deciding which month owns a journey that starts in
one and ends in the other buys nothing, and paying it at both ends is the same
answer the previous rule gave.

---

## 3. The entitlement is dated, per employee

`TRANSPORT_PER_DAY` is a BOOLEAN value in `employee_payroll_value_history` with a
`valid_from`. Before that date there is **no transport**, rather than a silent
one.

This is what `2026-09-10-01` introduced and why: the mode used to mean "everyone
without a fixed amount", which read nothing about the employee, so it had no
start date and every month anybody had ever worked would gain transport the next
time it was recalculated.

**A FALSE row is a decision, not an absence.** `EmployeePayrollValueService.trueFlagsOn`
only collects TRUE values, so a FALSE row means "this employee is explicitly not
paid per-arrival transport from this date". Employee 6 has one, from 2026-08-07.
Backfills must never overwrite or extend it.

### Start dates

| | |
|---|---|
| `2026-09-10-01` backfilled | first month not yet calculated — **2026-09-01** |
| `2026-09-13-01` backdated to | **`GREATEST('2025-01-01', employment_start_date)`** |

The second date is the owner's answer to when transport actually started. The
employment start date is used where it is later, because an entitlement that
predates the employment is meaningless and pays exactly the same — there is no
work to count before somebody was hired.

`2026-09-13-01` also creates the entitlement for **archived** employees, which
`2026-09-10-01` skipped. A month somebody actually worked is a month they
actually travelled, and their payslip for it still has to be right. That is what
fixes an employee who left in July 2026 showing zero transport for the days they
worked before leaving.

---

## 4. Why a transport line can read 0,00

Each is an explained zero, surfaced on the payslip through `ZERO_REASONS` in the
frontend's `adjustmentPolicy.ts`. An unexplained 0,00 cannot be told apart from a
fault — and was in fact reported as one.

| Reason | Means |
|---|---|
| `NO_TRANSPORT_ENTITLEMENT` | neither mode is in force on this date |
| `NO_TRANSPORT_RATE_CONFIGURED` | per-arrival mode, but no company rate is set |
| `NO_DAYS_WORKED` | entitled and priced, but no qualifying shift in the month |
| `NO_EMPLOYEE` | the item has no employee — a data fault |

---

## 5. Applying the backdating

**Applied to `marel_app` on 2026-08-05**, with a `pg_dump` taken first. Nothing
recalculates from a data change alone, so the sweep below was run straight after.

```bash
# rehearse against a clone first — this is the project's standard practice
createdb marel_rehearsal
pg_dump -d marel_app --no-owner --no-privileges | psql -q -d marel_rehearsal
psql -v ON_ERROR_STOP=1 -d marel_rehearsal \
  -f src/main/resources/sql/2026-09-13-01-transport-entitlement-from-2025.sql

# then the real database
psql -v ON_ERROR_STOP=1 -d marel_app \
  -f src/main/resources/sql/2026-09-13-01-transport-entitlement-from-2025.sql
```

Result on `marel_app`, identical to the rehearsal: **97 entitlements moved back,
3 created**. A second run is a clean no-op.

### Recalculating

Nothing recalculates from a data change alone. `PayrollRecalculationRunner`
exists for exactly this and needs no HTTP call or password:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--app.payroll.recalculate-on-startup=true --server.port=0"
```

`--server.port=0` lets it run beside a development instance instead of fighting
it for 8080. **Stop any instance running OLDER code first** — two versions of the
payroll calculator against one database is how an unexplainable payslip is made.

Result: **849 visited, 849 recalculated, 0 failed.** The schema was dumped before
and after and is byte-identical, so `ddl-auto=update` changed nothing.

### What recalculation changed

Both directions:

- **2026-07 gained transport** — employee 3's July went from `0.00` to `400.00`
  (2 arrivals × 200).
- **Per-shift figures went down** where a day held two consecutive shifts —
  employee 3's June is 8 shifts but **7 arrivals**.

No payroll item in this database is `LOCKED`, so nothing was protected. Lock the
months that are settled before recalculating again.

---

## 6. Where it lives

| | |
|---|---|
| Counting rule | `DailyReportRepository.countQualifyingArrivals` |
| Mode selection, rate, zero reasons | `payroll_calculation/calculators/TransportAllowanceCalculator` |
| Threshold | `TransportAllowanceCalculator.ARRIVAL_GAP_MINUTES` |
| Entitlement values | `EmployeePayrollValueCodes.TRANSPORT_PER_DAY` / `TRANSPORT_FIXED_MONTHLY` |
| Tests | `TransportPerArrivalIT` (the rule), `PayrollGoldenSnapshotIT` §15 (the money) |
| Payslip zero labels | frontend `features/payrolls/domain/adjustmentPolicy.ts` |

> The value code is still spelled `TRANSPORT_PER_DAY` although it now means per
> arrival. A definition code is an identifier, not a description, and renaming it
> would break every row that references it.

---

## 7. The fixed-amount gap, and the one employee still open

### Closed: the 37 employees whose fixed amount starts 2026-08-01 — `2026-09-14-01`

`2026-09-13-01` excluded anyone holding a `TRANSPORT_FIXED_MONTHLY` value, so
that nobody ends up on two modes. That exclusion asks whether a fixed amount
EXISTS, not when it STARTS — and every fixed amount here begins **2026-08-01**,
while all 37 employees were hired before that. They therefore held neither value
for June and July 2026 and were paid nothing.

The owner confirmed they **were** reimbursed per arrival before the fixed
arrangement began. `2026-09-14-01` gives each of them one **closed** period:

```
GREATEST(2025-01-01, employment_start_date)  ..  fixed.valid_from - 1
```

**Closed rather than open-ended is the whole point.** An open-ended row would
overlap the fixed period and be refused by `ex_epvh_no_overlap` — the constraint
doing its job, because "paid a fixed monthly amount AND paid per arrival" is not
a state this system may represent. Ending the day before makes the handover
exact. Verified after applying: **zero** employees hold both modes on any date.

### Still open: Iva Đurđević (employee 6)

Her `TRANSPORT_PER_DAY` history is `FALSE` from **2026-08-07** and `TRUE` from
**2026-09-01**. Before 2026-08-07 she has no row at all, so June, July and the
first six days of August pay no transport. She is now the **only** employee in
the database with `NO_TRANSPORT_ENTITLEMENT` in any month.

Both backfills left her alone deliberately: `2026-09-13-01` only inserts for
employees with no row, and only moves a row back when no earlier period exists —
hers has one. `2026-09-14-01` does not see her because she holds no fixed amount.

**The question is whether the months before 2026-08-07 should be paid.** A `FALSE`
row reads like a withdrawal — you do not revoke what nobody had — but that is an
inference, and a FALSE row is a decision that no backfill may quietly reinterpret.
If the answer is yes, the fix is one closed period ending 2026-08-06:

```sql
-- NOT APPLIED. One employee, so the intended path is the application:
-- EmployeePayrollValueService.changeValue accepts a date before every existing
-- period, closes the gap, and audits who did it.
```

---

## 8. Lines that keep a human figure

Five transport lines carry an `amount` that differs from `system_amount`, all
with `has_manual_input = true`: somebody typed the figure in. `syncAdjustment` is
called with `writeAmount = false` for those, so the human number is kept and
`system_amount` records what the calculation would have paid.

| Employee | Period | Calculated | Paid |
|---:|---|---:|---:|
| 2 | 2026-01 | 0.00 | 3 000.00 |
| 3 | 2026-06 | 1 400.00 | 1 900.00 |
| 5 | 2026-06 | 0.00 | 20 000.00 |
| 4 | 2026-07 | 200.00 | 6 000.00 |
| 17 | 2026-08 | 400.00 | 1 000.00 |

This is by design and predates the per-arrival change. It is listed because the
recalculation moved `system_amount` underneath those figures, so the gap between
what is paid and what the rule now produces is visible — and larger — than it was.
Clearing a line's manual input makes the calculation take over.
