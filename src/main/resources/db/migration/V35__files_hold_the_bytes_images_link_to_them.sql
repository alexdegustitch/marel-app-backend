-- =============================================================================
-- Files hold the bytes; images link to them
-- =============================================================================
-- WHAT CHANGES
--   · files — a new table: one row per stored object (its storage key, original
--     name, content type, size, who uploaded it). The bytes live in object
--     storage (Cloudflare R2 in prod, local disk in dev); this row is only the
--     metadata and the KEY that finds them.
--   · images — reworked to point at a file instead of carrying a URL:
--       – DROP COLUMN url (+ its not-empty check)
--       – ADD COLUMN file_id → files(id)
--     The owner columns, is_primary and sort_order are unchanged.
--
-- WHY A GENERIC files TABLE
--   The same store will hold product images today and employee photos, documents,
--   PDFs and production-order attachments tomorrow. Keeping the byte metadata in
--   one table (files) and the domain meaning in a thin link (images: which owner,
--   which is primary, in what order) lets every future attachment reuse files
--   without repeating the storage-key/content-type/size columns.
--
-- WHY THE KEY, NOT A URL
--   files.storage_key holds the object key ("products/842/uuid.webp"), never a
--   full "https://<bucket>.r2.cloudflarestorage.com/…" URL. The bucket, the
--   provider and the region are deployment configuration, not data: storing the
--   key means moving from R2 to S3, MinIO or local disk changes a config value
--   and no row. The download URL is generated (and, for a private bucket, signed
--   and short-lived) at read time by the FileStorage implementation.
--
-- WHY REWORKING images IS SAFE
--   images was created empty in V34 and still holds zero rows, so url is dropped
--   and file_id added NOT NULL with no back-fill and no data lost. Had any image
--   existed, this would have needed a migration of its url into a file row first.
--
-- MIGRATION IMPACT
--   · files is new and empty; nothing reads or writes it until the file upload
--     code ships. Registered for auditing.
--   · images changes shape but holds no rows; no query breaks because nothing has
--     selected images yet (the feature is unbuilt).
--   · uploaded_by is ON DELETE SET NULL: a file outlives the account that
--     uploaded it. The FK to files from images is ON DELETE CASCADE: deleting a
--     file removes the links that pointed at it (the service deletes the object
--     and the row together).
--   · Guarded and replayable.
--   · Rollback:
--       ALTER TABLE public.images DROP COLUMN file_id;
--       ALTER TABLE public.images ADD COLUMN url character varying(500);
--       ALTER TABLE public.images ADD CONSTRAINT chk_images_url_not_empty
--           CHECK (length(btrim(url)) > 0);
--       DROP TABLE public.files;
--       DELETE FROM public.audit_tables WHERE table_name = 'files';
-- =============================================================================


-- ── files ────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.files (
    id                bigint GENERATED ALWAYS AS IDENTITY,

    -- The object key inside the bucket, e.g. "products/842/<uuid>.webp".
    -- Unique: one row per stored object.
    storage_key       character varying(500) NOT NULL,

    -- The name the user's file arrived under — kept for display and download,
    -- never used as the key (the key is generated on the server).
    original_filename character varying(255),
    content_type      character varying(100),
    size_bytes        bigint,

    -- Who uploaded it; the file outlives the account, so ON DELETE SET NULL.
    uploaded_by       bigint,

    created_at        timestamp with time zone NOT NULL DEFAULT now(),

    CONSTRAINT pk_files PRIMARY KEY (id),
    CONSTRAINT chk_files_storage_key_not_empty
        CHECK (length(btrim(storage_key)) > 0),
    CONSTRAINT fk_files_uploaded_by
        FOREIGN KEY (uploaded_by) REFERENCES public.users (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_files_storage_key
    ON public.files (storage_key);


-- ── images: point at a file, not a URL ───────────────────────────────────────

ALTER TABLE public.images
    DROP CONSTRAINT IF EXISTS chk_images_url_not_empty;

ALTER TABLE public.images
    DROP COLUMN IF EXISTS url;

ALTER TABLE public.images
    ADD COLUMN IF NOT EXISTS file_id bigint;

-- images is empty, so NOT NULL can be set with no back-fill.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'images' AND column_name = 'file_id'
          AND is_nullable = 'YES'
    ) THEN
        ALTER TABLE public.images ALTER COLUMN file_id SET NOT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_images_file'
    ) THEN
        ALTER TABLE public.images
            ADD CONSTRAINT fk_images_file
            FOREIGN KEY (file_id) REFERENCES public.files (id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_images_file
    ON public.images (file_id);


-- ── Auditing files ───────────────────────────────────────────────────────────

INSERT INTO public.audit_tables (table_name)
SELECT 'files'
WHERE NOT EXISTS (
    SELECT 1 FROM public.audit_tables WHERE table_name = 'files'
);

DROP TRIGGER IF EXISTS trg_audit_logs_files ON public.files;
CREATE TRIGGER trg_audit_logs_files
    AFTER INSERT OR DELETE OR UPDATE ON public.files
    FOR EACH ROW EXECUTE FUNCTION public.audit_trigger_fn();


-- ── What the columns mean ────────────────────────────────────────────────────

COMMENT ON TABLE public.files IS
    'One row per stored object. The bytes live in object storage; storage_key finds them. Reused for images, documents, PDFs and any future attachment.';
COMMENT ON COLUMN public.files.storage_key IS
    'The object key inside the bucket (e.g. "products/842/<uuid>.webp"). Never a full provider URL — the bucket/provider/region are configuration, not data.';
COMMENT ON COLUMN public.images.file_id IS
    'The stored file this image row shows. The owner columns say whose it is; is_primary and sort_order say which and in what order.';
