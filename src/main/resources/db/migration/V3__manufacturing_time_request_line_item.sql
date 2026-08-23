-- A manufacturing-time request can name the production-order line item it came
-- from.
--
-- WHY. Commercial staff meet the need on a concrete order: a line has no
-- manufacturing time, or the one it has no longer matches. Until now the request
-- could only say WHICH PRODUCT it was about, so the reason it was raised lived in
-- the free-text description or nowhere at all. Recording the line item makes the
-- occasion part of the record: a processor sees which order is waiting, and the
-- order can show that a request is already open for one of its lines.
--
-- The line item is the OCCASION, not the subject. `product_id` stays NOT NULL and
-- keeps its meaning: manufacturing times hang off a product
-- (`product_manufacturing_times.product_id`), so a request with only a line item
-- would leave completion with nothing to write. NULL therefore means exactly
-- "raised on its own", not "about nothing".
--
-- Additive only: one nullable column, one foreign key, two indexes. No existing
-- row changes, and every request written before this migration stays valid as a
-- standalone request.

ALTER TABLE manufacturing_time_requests
    ADD COLUMN production_order_line_item_id bigint;

-- The pair (line item, product) is what the foreign key checks, so the two can
-- never drift apart -- a request cannot claim to come from a line for product A
-- while asking for a manufacturing time on product B. Enforcing it in the schema
-- rather than in the service is what makes it true for every writer, including
-- SQL run by hand. This unique constraint exists only to be that key's target;
-- `id` is already the primary key, so it forbids nothing new.
ALTER TABLE production_order_line_items
    ADD CONSTRAINT uq_production_order_line_items_id_product UNIQUE (id, product_id);

-- MATCH SIMPLE (the default) is deliberate: with production_order_line_item_id
-- NULL the constraint is not checked at all, which is precisely the standalone
-- request. RESTRICT matches every other foreign key on this table -- a line item
-- somebody asked about is not silently removable.
ALTER TABLE manufacturing_time_requests
    ADD CONSTRAINT fk_manufacturing_time_requests_line_item
        FOREIGN KEY (production_order_line_item_id, product_id)
        REFERENCES production_order_line_items (id, product_id)
        ON DELETE RESTRICT;

-- Partial: most requests are standalone, and the only question ever asked of this
-- column is "which requests belong to this line".
CREATE INDEX idx_manufacturing_time_requests_line_item
    ON manufacturing_time_requests (production_order_line_item_id)
    WHERE production_order_line_item_id IS NOT NULL;
