-- =============================================================================
-- Ten customers, for a development database
-- =============================================================================
-- NOT A MIGRATION, and deliberately not in db/migration. Flyway runs everything
-- in that folder against production too, and invented companies with invented
-- tax numbers have no business in a real customer list.
--
-- Run it by hand against a dev database:
--
--   psql -U <user> -d marel_app -f scripts/seed-dev-customers.sql
--
-- Idempotent: re-running inserts nothing, because each row is matched on its
-- code. Every name and tax number below is fictional.
--
-- Removing them again:
--   DELETE FROM customers WHERE code LIKE 'DEV-%';
-- =============================================================================

INSERT INTO public.customers (code, name, tax_id, website, email, phone, is_active)
SELECT seed.code, seed.name, seed.tax_id, seed.website, seed.email, seed.phone, seed.is_active
FROM (
    VALUES
        ('DEV-ACME', 'Acme Ambalaža d.o.o.',      '100200301', 'acme-ambalaza.rs',   'nabavka@acme-ambalaza.rs',  '+381641110001', true),
        ('DEV-BPL',  'Balkan Plast d.o.o.',       '100200302', 'balkanplast.rs',     'office@balkanplast.rs',     '+381641110002', true),
        ('DEV-DUN',  'Dunav Komerc d.o.o.',       '100200303', NULL,                 'komercijala@dunav-k.rs',    '+381641110003', true),
        ('DEV-VOJ',  'Vojvodina Pak d.o.o.',      '100200304', 'vojvodinapak.rs',    'porudzbine@vojvodinapak.rs', '+381641110004', true),
        ('DEV-MOR',  'Morava Inženjering d.o.o.', '100200305', NULL,                 NULL,                        '+381641110005', true),
        -- No code at all: a customer known only by name is a customer, and the
        -- screens have to cope with the column being empty.
        (NULL,       'Zlatibor Trade',            '100200306', NULL,                 'info@zlatibortrade.rs',     NULL,            true),
        ('DEV-JAD',  'Jadran Logistika d.o.o.',   '100200307', 'jadran-log.rs',      'transport@jadran-log.rs',   '+381641110007', true),
        -- No tax number: a foreign buyer who has none here.
        ('DEV-ALP',  'Alpen Verpackung GmbH',     NULL,        'alpen-verp.example', 'einkauf@alpen-verp.example', '+4917611110008', true),
        ('DEV-SAV',  'Sava Metal d.o.o.',         '100200309', NULL,                 'nabavka@savametal.rs',      '+381641110009', true),
        -- Deactivated, so the list has something to show as inactive and the
        -- picker has something it must refuse to offer.
        ('DEV-TIM',  'Timok Prerada d.o.o.',      '100200310', NULL,                 NULL,                        NULL,            false)
) AS seed(code, name, tax_id, website, email, phone, is_active)
WHERE NOT EXISTS (
    SELECT 1 FROM public.customers c
    WHERE (seed.code IS NOT NULL AND lower(c.code) = lower(seed.code))
       OR (seed.code IS NULL AND c.name = seed.name)
);

-- The one deactivated row needs its archived_at, which the trigger only writes
-- on an UPDATE from active to inactive — an INSERT never fires it.
UPDATE public.customers
SET archived_at = now() - interval '90 days'
WHERE code = 'DEV-TIM' AND is_active = false AND archived_at IS NULL;
