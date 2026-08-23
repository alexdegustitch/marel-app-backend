-- The manufacturing time that answers a request, and the rule that a finished
-- request always has one.
--
-- WHY THE LINK LIVES HERE. A manufacturing time may answer MANY requests: two
-- people can ask for the same product's time, and one record settles both. That
-- is many-to-one, so the foreign key belongs on the "many" side -- the request.
--
-- The existing link, product_manufacturing_times.source_request_id, cannot carry
-- this: it is one column on the record, so the record can name exactly one
-- request. A second request answered by the same record would overwrite the
-- first, leaving a COMPLETED request with nothing to show for it.
-- uq_pmt_source_request_id does not prevent that -- it enforces the opposite
-- direction, one record per request.
--
-- BOTH COLUMNS STAY, answering different questions:
--
--   manufacturing_time_requests.result_manufacturing_time_id
--       Which record ANSWERS this request?  many requests -> one record.
--       Written whenever a request is completed, whether the record was newly
--       produced or an existing one was attached.
--
--   product_manufacturing_times.source_request_id
--       Which request last WROTE this record?  one record <-> one request.
--       Unchanged, and deliberately NOT written when an existing record is
--       merely attached to a request -- attaching is not authorship.
--
-- So the two can never contradict each other: one records authorship, the other
-- records the answer.

ALTER TABLE manufacturing_time_requests
    ADD COLUMN result_manufacturing_time_id bigint;

-- Every request completed before this migration produced its record through
-- complete(), which stamped source_request_id. That is exactly the answer link
-- for those rows, so the new column starts out already true.
UPDATE manufacturing_time_requests r
   SET result_manufacturing_time_id = p.id
  FROM product_manufacturing_times p
 WHERE p.source_request_id = r.id;

-- The target of the composite key below. `id` is already the primary key, so
-- this forbids nothing new.
ALTER TABLE product_manufacturing_times
    ADD CONSTRAINT uq_pmt_id_product UNIQUE (id, product_id);

-- Same idiom as fk_manufacturing_time_requests_line_item: the pair is checked as
-- one key, so a request cannot be answered by a record for a different product.
-- MATCH SIMPLE is deliberate -- with no result yet, nothing is checked.
ALTER TABLE manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_result
        FOREIGN KEY (result_manufacturing_time_id, product_id)
        REFERENCES product_manufacturing_times (id, product_id)
        ON DELETE RESTRICT;

CREATE INDEX idx_manufacturing_time_requests_result
    ON manufacturing_time_requests (result_manufacturing_time_id)
    WHERE result_manufacturing_time_id IS NOT NULL;

-- Fail with something a person can act on, rather than letting the constraint
-- below reject the table with a raw violation.
DO $$
DECLARE
    completed_without_result bigint;
    unfinished_with_result   bigint;
BEGIN
    SELECT count(*) INTO completed_without_result
      FROM manufacturing_time_requests
     WHERE status = 'COMPLETED' AND result_manufacturing_time_id IS NULL;

    SELECT count(*) INTO unfinished_with_result
      FROM manufacturing_time_requests
     WHERE status <> 'COMPLETED' AND result_manufacturing_time_id IS NOT NULL;

    IF completed_without_result > 0 OR unfinished_with_result > 0 THEN
        RAISE EXCEPTION
            'Ne mogu da uvedem chk_manufacturing_time_requests_result_state: % zavrsenih zahteva bez rezultata, % nezavrsenih sa rezultatom. Popravite te redove pa ponovite migraciju.',
            completed_without_result, unfinished_with_result;
    END IF;
END $$;

-- "A finished request always has a result; an unfinished or refused one never
-- does" stops being a rule only the service remembers. It is the schema half of
-- the same guarantee as chk_manufacturing_time_requests_processing_state.
ALTER TABLE manufacturing_time_requests
    ADD CONSTRAINT chk_manufacturing_time_requests_result_state
        CHECK (((status)::text = 'COMPLETED') = (result_manufacturing_time_id IS NOT NULL));
