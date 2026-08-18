-- =============================================================================
-- Who may see, and change, which payroll line
-- =============================================================================
-- THE CHANGE
-- Payroll visibility has been one blunt rule in code: admin and developer see
-- every figure, everybody else sees none. The owner wants it per LINE, and
-- configurable from the application rather than by a deploy — which is the
-- condition under which a table earns its place over a constant in Java.
--
-- The catalogue this hangs off already exists. payroll_adjustment_categories
-- has 13 rows and already carries presentation flags (visible_in_ui,
-- visible_in_pdf, is_manual). What was missing is the ROLE dimension, so this
-- is not a new mechanism; it is the missing axis of one already here.
--
-- WHY A JOIN TABLE AND NOT TWO COLUMNS ON THE CATEGORY
-- "editable" and "visible" are not properties of a line. They are properties of
-- a line FOR SOMEBODY: the same bonus is a supervisor's business and not a
-- commercial clerk's. A boolean on the category has no room for "for whom", and
-- the moment it grows one it is this table.
--
-- ADMIN AND DEVELOPER GET NO ROWS
-- They bypass this table entirely. Two reasons, both practical: an
-- administrator must not be able to lock payroll out of payroll by editing a
-- screen, and with them excluded "no row" can mean HIDDEN, so the table fails
-- closed. The CHECK states that rather than trusting everyone to remember.
--
-- FIELD CODES COME FROM TWO PLACES, SO THERE IS NO FOREIGN KEY
-- Most codes are payroll_adjustment_categories.code. A few name figures on the
-- item itself (NET_PAYABLE, TOTAL_NET_EARNINGS, HOURLY_RATE), which are columns
-- and not rows anywhere. A FK would have to point at one of the two and would
-- forbid the other.
--
-- IMPACT
-- New table only; nothing existing is altered. IT STARTS EMPTY, AND THAT IS THE
-- POINT: no row means not visible, which is exactly what non-payroll roles see
-- today. Introducing the configuration therefore changes nobody's screen until
-- an administrator grants something. Reversible with DROP TABLE.
-- =============================================================================

CREATE TABLE payroll_field_access (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- A payroll_adjustment_categories.code, or one of the item-level figures.
    field_code   VARCHAR(255) NOT NULL,
    role_name    VARCHAR(255) NOT NULL,

    can_view     BOOLEAN NOT NULL DEFAULT FALSE,
    -- Editing without seeing is not a state anybody asked for, and it would let
    -- somebody overwrite a figure they cannot read.
    can_edit     BOOLEAN NOT NULL DEFAULT FALSE,

    -- Changing who may see a salary is itself worth attributing.
    updated_by   BIGINT REFERENCES users (id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_pfa_field_role UNIQUE (field_code, role_name),
    CONSTRAINT chk_pfa_not_payroll_role
        CHECK (lower(role_name) NOT IN ('admin', 'developer')),
    CONSTRAINT chk_pfa_edit_requires_view
        CHECK (NOT can_edit OR can_view)
);

CREATE INDEX idx_pfa_role ON payroll_field_access (role_name);

COMMENT ON TABLE payroll_field_access IS
    'Per-line payroll visibility by role. Admin and developer are absent by '
    'design and bypass it; a missing row means hidden.';
