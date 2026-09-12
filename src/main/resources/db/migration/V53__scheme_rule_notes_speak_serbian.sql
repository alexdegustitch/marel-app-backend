-- =============================================================================
-- The scheme-rule and scheme notes speak Serbian
-- =============================================================================
-- WHAT CHANGES
--   Data only. The compensation-scheme rule notes, the effective category's
--   note and the three built-in scheme descriptions were seeded in English in
--   V1. This translates them to Serbian in place, matching each English text
--   exactly so only those rows are touched and a rerun changes nothing.
--
-- WHY A MIGRATION AND NOT A SEED EDIT
--   V1 is an applied baseline — its checksum is frozen. The English text lives
--   as data in a running database, so the only way to reach it is an UPDATE
--   that runs on every environment.
--
-- WHY IT IS SAFE
--   Notes and descriptions are free-text shown to the administrator; nothing in
--   payroll or norm calculation reads them. The audit trigger records each
--   change (old → new) with a null user, exactly as any system-run write does,
--   so auditability is preserved. No schema change.
-- =============================================================================

-- ── work_code_category_scheme_rules.note ────────────────────────────────────
UPDATE work_code_category_scheme_rules
SET note = $$Fiksni koeficijent: pod ovim načinom obračuna svaka smena i svaki posao vrede isto.$$
WHERE note = $$Fixed coefficient: every shift and every trade is worth the same under this scheme.$$;

UPDATE work_code_category_scheme_rules
SET note = $$Fiksni koeficijent. Bira se kao svaka druga kategorija rada — administrator unese šta je rađeno, a sistem to preslikava.$$
WHERE note = $$Fixed coefficient. Selectable like any other work category — the supervisor enters what was worked and the backend maps it.$$;

UPDATE work_code_category_scheme_rules
SET note = $$Cilj obračuna. Rad postaje ova kategorija TEK nakon preslikavanja, pa je ovde potpuno definisana, ali se nikad ne bira direktno.$$
WHERE note = $$Calculation target. Work becomes this AFTER the mapping, so it is fully defined here but never offered for selection.$$;

UPDATE work_code_category_scheme_rules
SET note = $$Samo cilj obračuna: rad ovde stiže nakon primene načina obračuna, pa se nikad ne bira direktno. I dalje se plaća — pravila koja se preslikavaju na nju čine da se pojavi na platnom listiću.$$
WHERE note = $$Calculation target only: work lands here after the scheme is applied, so it is never selected directly. Still payable — the rules that remap onto it are what make it appear on the payroll sheet.$$;

-- Older variant that carries the source category number in the text.
UPDATE work_code_category_scheme_rules
SET note = $$Fiksni koeficijent: pod ovim načinom obračuna svaka smena vredi isto. Izvorna kategorija se čuva na radnom nalogu.$$
WHERE note LIKE $$Fixed coefficient: every shift is worth the same under this scheme.%$$;

-- ── work_code_categories.note (the FOREIGN effective category) ───────────────
UPDATE work_code_categories
SET note = $$Zajednička efektivna kategorija za način obračuna „Fiksni koeficijent". Nikad se ne bira direktno na radnom nalogu: dobija se iz izvorne kategorije (J, D, ...) preko pravila načina obračuna. Izvorna kategorija se uvek čuva.$$
WHERE note = $$Common effective category for the FOREIGN_FIXED_COEFFICIENT scheme. Never selected directly on a work log: it is resolved from a source category (J, D, ...) by work_code_category_scheme_rules. The source category is always preserved.$$;

-- ── compensation_schemes.note ────────────────────────────────────────────────
UPDATE compensation_schemes
SET note = $$Podrazumevani način. Kategorije se ponašaju kao i pre uvođenja načina obračuna: svaka aktivna kategorija može da se izabere, a koeficijent dolazi iz koeficijenta norme kategorije.$$
WHERE code = 'STANDARD'
  AND note = $$Default policy. Categories behave exactly as they did before compensation schemes existed: every active category is selectable and the coefficient comes from work_code_categories.norm_multiplier.$$;

UPDATE compensation_schemes
SET note = $$Ograničeni način: mogu da se izaberu samo kategorije koje imaju izričito pravilo za ovaj način obračuna, a smenske kategorije se svode na jednu efektivnu kategoriju sa koeficijentom 1.$$
WHERE code = 'FOREIGN_FIXED_COEFFICIENT'
  AND note = $$Restricted policy: only categories with an explicit work_code_category_scheme_rules row may be selected, and the shift categories resolve to a single effective category with coefficient 1.$$;

UPDATE compensation_schemes
SET note = $$Kategorije rada se ponašaju kao u standardnom obračunu. Bez satnog bonusa: bonus učinka je isključen (nula), a pravilo mesečnog bonusa zadržava stavku vidljivom na nuli.$$
WHERE code = 'COMMERCIAL'
  AND note = $$Work categories behave as under STANDARD. No hourly bonus: allows_performance_bonus = false zeroes the category bonus, and the MONTHLY_BONUS rule keeps the line visible at zero.$$;
