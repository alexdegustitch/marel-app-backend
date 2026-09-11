-- =============================================================================
-- The board's thresholds become settings somebody can tune
-- =============================================================================
-- WHAT CHANGES
--   Five app_settings keys, seeded with the values the control board has been
--   using as constants (or close to them — see below). The snapshot job reads
--   them at compute time, so changing one in Parametri changes what tomorrow's
--   board (or the next "Osveži") calls worth looking at:
--
--     dashboard_norm_rise_pct         — a norm is "preniska" when the measured
--                                       rate sits this many % above 100.
--     dashboard_norm_drop_pct         — "previsoka", the same distance below.
--     dashboard_norm_window_days      — how far back the norm cards look.
--     dashboard_activity_window_days  — how far back "Šta se radilo" looks
--                                       (najviše/najmanje rađeno, najbolji).
--     dashboard_top_performer_min_hours — hours of recorded work on normed
--                                       operations before a person is ranked.
--
-- WHY
--   The owner asked for the norm deviation to be tunable (10 % now, maybe 5 %
--   later) with rise and drop separately, and for the windows to be visible and
--   changeable rather than baked in. These are DISPLAY thresholds: nothing is
--   paid or calculated from them, they only choose which rows reach a card —
--   which is exactly why they belong beside the other screen-facing settings
--   in Parametri and not in code.
--
-- WHAT HAPPENS TO EXISTING DATA
--   Nothing is altered or deleted; five rows are inserted if absent. Note the
--   seeded values deliberately change the board's behaviour: the norm cards
--   move from ±15 pp over 30 days to ±10 % over 90 days — the thresholds the
--   owner asked for — and the performer minimum stays at the 20 h the constant
--   encoded. Existing snapshots stay as computed; the next run uses these.
--
-- Rollback: DELETE FROM app_settings WHERE setting_key IN (
--   'dashboard_norm_rise_pct','dashboard_norm_drop_pct',
--   'dashboard_norm_window_days','dashboard_activity_window_days',
--   'dashboard_top_performer_min_hours');
-- =============================================================================

INSERT INTO app_settings (
    setting_key, value_type, setting_value_numeric, unit, display_text, description,
    is_active, affects_payroll, valid_from
)
SELECT v.key, 'number', v.value, v.unit, v.display_text, v.description, true, false, now()
FROM (VALUES
    ('dashboard_norm_rise_pct', 10::numeric, '%',
     'Kontrolna tabla — porast učinka za proveru norme',
     'Operacija se prijavljuje kao „norma je preniska" kada je prosečan učinak iznad norme za ovoliko procenata (npr. 10 = prijavljuje se od 110%). Samo prikaz na kontrolnoj tabli — ne utiče na obračun.'),
    ('dashboard_norm_drop_pct', 10::numeric, '%',
     'Kontrolna tabla — pad učinka za proveru norme',
     'Operacija se prijavljuje kao „norma je previsoka" kada je prosečan učinak ispod norme za ovoliko procenata (npr. 10 = prijavljuje se do 90%). Samo prikaz na kontrolnoj tabli — ne utiče na obračun.'),
    ('dashboard_norm_window_days', 90::numeric, 'dana',
     'Kontrolna tabla — period provere normi',
     'Koliko poslednjih dana ulazi u proveru normi (preniska/previsoka). Samo prikaz na kontrolnoj tabli — ne utiče na obračun.'),
    ('dashboard_activity_window_days', 30::numeric, 'dana',
     'Kontrolna tabla — period pregleda rada',
     'Koliko poslednjih dana ulazi u „Šta se radilo": najviše i najmanje rađene operacije i najbolji radnici. Samo prikaz na kontrolnoj tabli — ne utiče na obračun.'),
    ('dashboard_top_performer_min_hours', 20::numeric, 'h',
     'Kontrolna tabla — najmanje sati za listu najboljih',
     'Radnik ulazi u „Najbolji" tek sa ovoliko sati upisanog rada na operacijama sa normom u izabranom periodu. Samo prikaz na kontrolnoj tabli — ne utiče na obračun.')
) AS v(key, value, unit, display_text, description)
WHERE NOT EXISTS (
    SELECT 1 FROM app_settings s WHERE s.setting_key = v.key
);
