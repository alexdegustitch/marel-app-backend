-- =============================================================================
-- STANJE audit trigera (samo čita, ništa ne menja)
-- =============================================================================
-- psql -d marel_app -f scripts/audit-triggers-status.sql
--
-- Prvi rezultat: zbirno koliko je aktivno / ugašeno.
-- Drugi: spisak tabela čiji je audit trigger UGAŠEN (prazno = sve beleži).
-- Treći: kad je poslednji red ušao u audit_logs — ako je trigger ugašen, ovo je
--        trenutak od kog trag više ne postoji.
-- =============================================================================

SELECT CASE t.tgenabled
           WHEN 'D' THEN 'UGAŠEN'
           WHEN 'O' THEN 'aktivan (origin)'
           WHEN 'A' THEN 'aktivan (always)'
           WHEN 'R' THEN 'aktivan samo u replica režimu'
       END AS stanje,
       count(*) AS trigera
  FROM pg_trigger t
  JOIN pg_proc p ON p.oid = t.tgfoid
 WHERE NOT t.tgisinternal
   AND p.proname = 'audit_trigger_fn'
 GROUP BY 1
 ORDER BY 1;

SELECT c.relname AS tabela,
       t.tgname  AS trigger
  FROM pg_trigger t
  JOIN pg_proc p  ON p.oid = t.tgfoid
  JOIN pg_class c ON c.oid = t.tgrelid
  JOIN pg_namespace ns ON ns.oid = c.relnamespace
 WHERE NOT t.tgisinternal
   AND ns.nspname = 'public'
   AND p.proname  = 'audit_trigger_fn'
   AND t.tgenabled = 'D'
 ORDER BY c.relname;

SELECT count(*)        AS audit_logs_redova,
       max(change_time) AS poslednji_upis
  FROM public.audit_logs;
