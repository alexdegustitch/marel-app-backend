-- =============================================================================
-- OPCIONO: upis display_name = product_name [+ ' ' + subtype] za sve proizvode
-- =============================================================================
-- display_name je po dizajnu OVERRIDE: kada je NULL, aplikacija sama prikazuje
-- product_name + subtype (ProductMapper.effectiveDisplayName), i to ime prati
-- svaku kasniju izmenu naziva ili subtipa.
--
-- Ovaj skript "zamrzava" trenutno izračunato ime u kolonu. Posle njega izmena
-- product_name ili subtype VIŠE NE MENJA prikazano ime tog proizvoda — dok se
-- display_name ručno ne isprazni ili ne prepiše. Dropdown-i i liste već
-- prikazuju izračunato ime i bez ovog skripta, pa ga pokrenuti samo ako
-- display_name zaista treba da postoji kao upisan podatak (npr. za eksterne
-- izveštaje koji čitaju bazu direktno).
--
-- Bezbedan za ponovno pokretanje: dira samo redove gde je display_name prazan.

UPDATE public.products
SET display_name = trim(product_name || ' ' || coalesce(subtype, ''))
WHERE display_name IS NULL OR btrim(display_name) = '';
