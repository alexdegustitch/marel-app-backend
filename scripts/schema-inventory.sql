-- Canonical, order-independent inventory of the `public` schema.
--
-- Emits one sorted line per schema object so two databases can be compared with
-- a plain textual diff. This is deliberately not `pg_dump --schema-only`: a dump
-- orders objects by dependency and OID, so two databases holding the identical
-- schema still produce different files when the migrations ran in a different
-- order. Production grew over months; the reference database is built in one
-- pass. They would never match textually even when they agree.
--
-- Definitions come from pg_get_constraintdef / pg_get_indexdef / pg_get_triggerdef
-- rather than from the catalog columns, because those functions normalise the
-- expression back to a canonical form - the same constraint written two ways
-- renders identically.
--
-- Usage: psql -At -f scripts/schema-inventory.sql <connection>

\pset format unaligned
\pset tuples_only on

SELECT line FROM (

    SELECT 'TABLE     ' || c.relname AS line
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'

    UNION ALL

    SELECT 'COLUMN    ' || c.relname || '.' || a.attname
           || ' ' || format_type(a.atttypid, a.atttypmod)
           || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE ' NULL' END
           || ' DEFAULT ' || COALESCE(pg_get_expr(d.adbin, d.adrelid), '-')
           || CASE WHEN a.attidentity <> '' THEN ' IDENTITY(' || a.attidentity::text || ')' ELSE '' END
           || CASE WHEN a.attgenerated <> '' THEN ' GENERATED(' || a.attgenerated::text || ')' ELSE '' END
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE n.nspname = 'public' AND c.relkind = 'r'
      AND a.attnum > 0 AND NOT a.attisdropped

    UNION ALL

    -- contype is included so a diff says *what kind* of constraint drifted
    SELECT 'CONSTRAINT ' || rel.relname || ' ' || con.conname
           || ' [' || con.contype::text || '] ' || pg_get_constraintdef(con.oid)
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace n ON n.oid = rel.relnamespace
    WHERE n.nspname = 'public'

    UNION ALL

    SELECT 'INDEX     ' || indexdef
    FROM pg_indexes
    WHERE schemaname = 'public'

    UNION ALL

    SELECT 'TRIGGER   ' || c.relname || ' ' || t.tgname || ' ' || pg_get_triggerdef(t.oid)
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND NOT t.tgisinternal

    UNION ALL

    -- enum labels carry their sort order: a label appended in the wrong position
    -- is a real difference, so enumsortorder is part of the line
    SELECT 'ENUM      ' || t.typname || ' ' || e.enumsortorder || ' ' || e.enumlabel
    FROM pg_type t
    JOIN pg_enum e ON e.enumtypid = t.oid
    JOIN pg_namespace n ON n.oid = t.typnamespace
    WHERE n.nspname = 'public'

    UNION ALL

    SELECT 'FUNCTION  ' || p.proname
           || '(' || pg_get_function_identity_arguments(p.oid) || ')'
           || ' -> ' || pg_get_function_result(p.oid)
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'

    UNION ALL

    SELECT 'SEQUENCE  ' || c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'S'

    UNION ALL

    SELECT 'VIEW      ' || c.relname
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind IN ('v', 'm')

) inventory
ORDER BY line;
