-- =============================================================================
-- A rate too good to be true is probably a typo — and it gets its own card
-- =============================================================================
-- WHAT CHANGES
--   One app_settings key, `dashboard_suspect_rate_pct` (default 250 %). The
--   board's new "Sumnjivi unosi" card lists shift+operation entries whose
--   UNCAPPED daily rate reaches this many percent of the norm.
--
-- WHY THE UNCAPPED RATE
--   The approved rate is clipped at `max_efficiency_percent`, so a quantity
--   with an extra zero in it quietly becomes "the maximum" and nothing looks
--   wrong. The uncapped rate is what the entry would have earned without the
--   ceiling — 900 % there is not a champion, it is a typo about to reach a
--   payslip. Display threshold only; nothing is paid or calculated from it.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing altered; one row inserted if absent. The dashboard_* prefix means
--   saving it recomputes the snapshot at once (see V44's listener).
--
-- Rollback: DELETE FROM app_settings WHERE setting_key = 'dashboard_suspect_rate_pct';
-- =============================================================================

INSERT INTO app_settings (
    setting_key, value_type, setting_value_numeric, unit, display_text, description,
    is_active, affects_payroll, valid_from
)
SELECT
    'dashboard_suspect_rate_pct',
    'number',
    250,
    '%',
    'Kontrolna tabla — prag sumnjivog učinka',
    'Unos se prijavljuje kao sumnjiv kada dnevni učinak na operaciji (BEZ ograničenja maksimalnog učinka) pređe ovoliko procenata norme — najčešće greška u unetoj količini. Samo prikaz na kontrolnoj tabli — ne utiče na obračun.',
    true,
    false,
    now()
WHERE NOT EXISTS (
    SELECT 1 FROM app_settings WHERE setting_key = 'dashboard_suspect_rate_pct'
);
