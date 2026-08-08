# Backend Instructions

This is the backend repo for the Marel Norm Tracking App.

## Rules

- Follow the existing backend architecture.
- Keep controllers thin if the project uses services.
- Validate inputs.
- Return clear error messages.
- Keep payroll and norm calculation logic testable.
- Explain database and migration impact before changing schema.
- Do not change business rules without asking.
- Preserve auditability and historical data.

## Business rules

Before modifying user approval, manufacturing-time requests, mailing lists,
production-order recipients, notifications, sessions or user preferences, read
`docs/business-rules/user-requests-mailing-notifications-and-preferences.md`.
`docs/business-rules/IMPLEMENTATION-STATUS.md` records what is actually built.

Before modifying employee compensation schemes, work-category availability or
coefficients, work-log coefficient snapshots, `work_code_category_mappings`, or
category name translations, read
`docs/business-rules/compensation-schemes-and-category-localization.md`.
Three category concepts coexist and must never be merged: the SOURCE category
(what was worked), the SCHEME-EFFECTIVE category (what the base calculation
uses), and the DERIVED category (the contextual night/weekend remap). Contextual
mappings are always keyed on the SOURCE category.

An employee on probation gets no weekend bonus (§13 of that document). This is
deliberately NOT a compensation scheme — probation is derived from the employment
dates rather than assigned, and it crosses the schemes instead of replacing one.
`work_code_category_mapping_types.applies_during_probation` says which remaps it
withholds, and `ProbationPolicy` says who is on probation on a given WORK DATE.
`ProbationPolicy` is also the single seam that employment-period history will
re-point; do not inline the check anywhere else.

Before working on the transport allowance, read
`docs/business-rules/transport-allowance.md`. Transport is paid per ARRIVAL —
per journey to work — which is neither per day nor per shift: consecutive shifts
are one journey, a shift after a break is another. Entitlement is a dated
per-employee value; an employee with none is paid nothing, and a FALSE row is a
decision that no backfill may overwrite.

Before working on the payroll document language, translations of category names,
or `employees.preferred_locale`, read `docs/business-rules/i18n-obracun.md`.
The application UI stays `sr-Latn`; only the payroll PDF is translated. The set of
locales is fixed and shipped — adding one touches `AppLocales.SUPPORTED`, the CHECK
on `employees.preferred_locale`, and the frontend JSON resources, in the same PR.
Note the current state: `PayrollRunItemService.getDetails` already falls back to the
employee's `preferred_locale` when no `?locale=` is sent, so a non-Serbian employee's
payslip already returns translated category and adjustment names while the PDF's own
labels are still hardcoded Serbian in `PayrollPdf.tsx`. That half-translated document
is the problem the plan solves, not a regression.
