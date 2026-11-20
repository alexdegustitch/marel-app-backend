-- =============================================================================
-- VRAĆA audit trigere na SVIM tabelama
-- =============================================================================
-- Pandan scripts/audit-triggers-off.sql: svakom triggeru koji poziva
-- audit_trigger_fn() i trenutno je ugašen vraća ALTER TABLE ... ENABLE TRIGGER.
--
-- Napomena o stanju: ENABLE vraća trigger u 'O' (origin) — radi u normalnim
-- sesijama, ne radi kad je session_replication_role = 'replica'. To je stanje u
-- kome su svi audit trigeri ovog projekta i bili. Ako neki trigger ikad bude
-- namerno postavljen na 'A' (ALWAYS, radi i u replica režimu), ova skripta ga
-- NE bi vratila na 'A' — proveri scripts/audit-triggers-status.sql posle.
--
-- Rupa u tragu: promene napravljene dok su trigeri bili ugašeni nisu nigde
-- zapisane i ne mogu se dopisati. Ova skripta vraća beleženje OD SADA.
--
-- Pokretanje
--   psql -d marel_app -f scripts/audit-triggers-on.sql
--   Idempotentno — ako je sve već aktivno, ne radi ništa.
-- =============================================================================

\set ON_ERROR_STOP on

SET lock_timeout = '5s';

BEGIN;

DO $$
DECLARE
    r       record;
    v_done  int := 0;
    v_total int;
BEGIN
    SELECT count(*) INTO v_total
      FROM pg_trigger t
      JOIN pg_proc p ON p.oid = t.tgfoid
     WHERE NOT t.tgisinternal
       AND p.proname = 'audit_trigger_fn';

    FOR r IN
        SELECT c.relname AS tbl, t.tgname AS trg
          FROM pg_trigger t
          JOIN pg_proc p  ON p.oid = t.tgfoid
          JOIN pg_class c ON c.oid = t.tgrelid
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE NOT t.tgisinternal
           AND ns.nspname = 'public'
           AND p.proname  = 'audit_trigger_fn'
           AND t.tgenabled = 'D'
         ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE TRIGGER %I', r.tbl, r.trg);
        v_done := v_done + 1;
    END LOOP;

    RAISE NOTICE '';
    RAISE NOTICE 'Vraćeno sada: % trigger(a). Ukupno audit trigera: %.', v_done, v_total;
    RAISE NOTICE 'Beleženje u audit_logs radi od ovog trenutka.';
    RAISE NOTICE '';
END $$;

COMMIT;

SELECT CASE t.tgenabled WHEN 'D' THEN 'UGAŠEN' ELSE 'aktivan' END AS stanje,
       count(*) AS trigera
  FROM pg_trigger t
  JOIN pg_proc p ON p.oid = t.tgfoid
 WHERE NOT t.tgisinternal
   AND p.proname = 'audit_trigger_fn'
 GROUP BY 1
 ORDER BY 1;
