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
