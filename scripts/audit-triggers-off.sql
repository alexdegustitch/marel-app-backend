-- =============================================================================
-- GASI audit trigere na SVIM tabelama (ne briše ih)
-- =============================================================================
-- Šta radi
--   Za svaki trigger koji poziva audit_trigger_fn() — danas ih je 68, po jedan
--   na 68 tabela, svi nazvani trg_audit_logs_<tabela> — izvršava
--   ALTER TABLE ... DISABLE TRIGGER. Triggeri OSTAJU u bazi, samo se ne
--   izvršavaju (pg_trigger.tgenabled ide iz 'O' u 'D').
--
--   Nijedan drugi trigger se ne dira: set_updated_at, set_archived_at_on_
--   deactivate, operations_history, recalc redovi, notifikacije i sve ostalo
--   rade dalje. Skripta ih prepoznaje po FUNKCIJI koju zovu, ne po imenu, pa
--   automatski pokriva i trigere dodate posle ovog fajla.
--
-- ŠTA OVO ZNAČI (pročitaj pre pokretanja)
--   Dok su trigeri ugašeni, NIJEDNA promena ne ulazi u audit_logs — ni iz
--   aplikacije, ni iz psql-a, ni iz bilo koje druge sesije. To nije „pauza":
--   te promene se NIKAD ne mogu rekonstruisati, jer trag koji bi ih zapisao
--   nije nastao. Rupa u tragu je trajna i nevidljiva — audit_logs posle
--   izgleda kao da se u tom periodu ništa nije dogodilo.
--
--   Zato: gasi samo za poznat, kratak, namerno nerevidiran posao (masovni
--   uvoz, migracija podataka, test performansi), i vrati ih istog dana.
--
-- ZAKLJUČAVANJE
--   DISABLE TRIGGER uzima ACCESS EXCLUSIVE lock na tabelu. Sve je u jednoj
--   transakciji, pa se lokovi drže do COMMIT-a — kratko (menja se samo
--   katalog), ali dovoljno da zablokira živu aplikaciju ako neka njena duga
--   transakcija drži tabelu. lock_timeout = 5s znači: ili prođe brzo, ili
--   padne i NIŠTA se ne promeni. Najbolje pokretati dok aplikacija ne radi.
--
-- Pokretanje
--   psql -d marel_app -f scripts/audit-triggers-off.sql
--   Idempotentno — ponovno pokretanje ne radi ništa.
--
-- Vraćanje
--   psql -d marel_app -f scripts/audit-triggers-on.sql
--   Stanje u svakom trenutku: scripts/audit-triggers-status.sql
-- =============================================================================

\set ON_ERROR_STOP on

SET lock_timeout = '5s';

BEGIN;

DO $$
DECLARE
    r        record;
    v_done   int := 0;
    v_total  int;
    v_last   timestamptz;
BEGIN
    SELECT count(*) INTO v_total
      FROM pg_trigger t
      JOIN pg_proc p ON p.oid = t.tgfoid
     WHERE NOT t.tgisinternal
       AND p.proname = 'audit_trigger_fn';

    SELECT max(change_time) INTO v_last FROM public.audit_logs;

    FOR r IN
        SELECT c.relname AS tbl, t.tgname AS trg
          FROM pg_trigger t
          JOIN pg_proc p  ON p.oid = t.tgfoid
          JOIN pg_class c ON c.oid = t.tgrelid
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE NOT t.tgisinternal
           AND ns.nspname = 'public'
           AND p.proname  = 'audit_trigger_fn'
           AND t.tgenabled <> 'D'          -- već ugašene preskači
         ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER TABLE public.%I DISABLE TRIGGER %I', r.tbl, r.trg);
        v_done := v_done + 1;
    END LOOP;

    RAISE NOTICE '';
    RAISE NOTICE 'Ugašeno sada: % trigger(a). Ukupno audit trigera: %.', v_done, v_total;
    RAISE NOTICE 'Poslednji upisani audit red: %', coalesce(v_last::text, 'nema ni jednog');
    RAISE NOTICE 'OD OVOG TRENUTKA promene se NE beleže. Vrati ih sa:';
    RAISE NOTICE '    psql -d marel_app -f scripts/audit-triggers-on.sql';
    RAISE NOTICE '';
END $$;

COMMIT;

-- Kontrola posle: koliko je ugašeno, koliko aktivno.
SELECT CASE t.tgenabled WHEN 'D' THEN 'UGAŠEN' ELSE 'aktivan' END AS stanje,
       count(*) AS trigera
  FROM pg_trigger t
  JOIN pg_proc p ON p.oid = t.tgfoid
 WHERE NOT t.tgisinternal
   AND p.proname = 'audit_trigger_fn'
 GROUP BY 1
 ORDER BY 1;
