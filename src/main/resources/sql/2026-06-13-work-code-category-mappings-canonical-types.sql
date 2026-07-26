-- Normalize work_code_category_mappings.mapping_type to canonical tokens.
-- mapping_type is free text entered via the UI/API; existing rows used inconsistent
-- values (different casing, reversed words, and a 'mulitple' typo) that did not match
-- the constants used by DailyRecalcService. This aligns the data to the canonical
-- WEEKEND_BONUS / NIGHT_SHIFT_BONUS / MULTIPLE_MACHINES_BONUS tokens.
-- Idempotent: re-running is a no-op once values are canonical.

UPDATE work_code_category_mappings
SET mapping_type = 'WEEKEND_BONUS'
WHERE lower(mapping_type) IN ('bonus_weekend', 'weekend_bonus');

UPDATE work_code_category_mappings
SET mapping_type = 'NIGHT_SHIFT_BONUS'
WHERE lower(mapping_type) IN ('night_shift_bonus', 'bonus_night_shift');

UPDATE work_code_category_mappings
SET mapping_type = 'MULTIPLE_MACHINES_BONUS'
WHERE lower(mapping_type) IN ('mulitple_machines_bonus', 'multiple_machines_bonus', 'bonus_multiple_machines');
