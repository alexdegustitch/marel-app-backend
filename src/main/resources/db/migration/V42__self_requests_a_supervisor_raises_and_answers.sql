-- A supervisor can now do a product's razrada and its vreme izrade themselves,
-- straight from a production order — without a colleague first raising the
-- request.
--
-- WHY. Until now the two request workflows were strictly two-sided: commercial
-- RAISES a request and the supervisor ANSWERS it. But the supervisor is often the
-- one who notices, on an order, that a line has no razrada or no manufacturing
-- time yet, and there is no reason to make them wait for someone to ask. The
-- answer they produce must still land where every other answer lands — visible to
-- commercial, and the denominator the order's progress is measured against — so it
-- travels through the SAME request tables and the SAME completion path rather than
-- a second, parallel concept.
--
-- The one thing a self-raised request must NOT do is appear in the queues people
-- pick work from: nobody asked for it, and it is already owned by the person doing
-- it. `internal = true` marks exactly those requests. The lists and pick queues
-- filter them out; the order the request was raised on, and the request's own
-- detail, still see it, because that is where the supervisor drives it and where
-- its result shows.
--
-- Additive only: one boolean column per table, defaulted false, NOT NULL. Every
-- request written before this migration is a normal, non-internal request, which
-- is exactly what a false default says. No existing row changes meaning, and the
-- audit triggers on both tables capture the new column like any other.

ALTER TABLE manufacturing_time_requests
    ADD COLUMN internal boolean NOT NULL DEFAULT false;

ALTER TABLE production_order_scope_requests
    ADD COLUMN internal boolean NOT NULL DEFAULT false;

-- The reuse lookups ("does this line already have an OPEN internal self-request
-- to return instead of raising a second one") ride on the existing line-item and
-- order indexes plus a status filter; the internal flag narrows an already-small
-- result, so no dedicated index is warranted. These partial indexes exist only to
-- keep the "hide internal from the queues" filter from ever scanning the whole
-- table as the history grows — they cover precisely the rows the filter keeps.
CREATE INDEX idx_manufacturing_time_requests_not_internal
    ON manufacturing_time_requests (status)
    WHERE internal = false;

CREATE INDEX idx_production_order_scope_requests_not_internal
    ON production_order_scope_requests (status)
    WHERE internal = false;
