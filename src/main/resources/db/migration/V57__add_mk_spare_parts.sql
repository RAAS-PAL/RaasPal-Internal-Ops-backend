-- MK spare parts: the parts RAAS PAL holds for MK, tracked on their own.
--
-- Deliberately separate from inventory_items / stock_movements (RAAS PAL's own stock) and
-- from monday: nothing else reads or writes these tables. Internal staff move stock in RIMS;
-- MK staff see the same numbers read-only, after entering a PIN we give them.
--
-- Additive only.

-- One row per part. quantity_on_hand is a cached balance: it changes only together with a
-- movement row, in the same transaction, so the ledger below stays the source of truth.
CREATE TABLE mk_spare_part (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    part_no           TEXT         NOT NULL,
    name              TEXT         NOT NULL,
    robot_model       TEXT,
    unit              TEXT         NOT NULL DEFAULT 'pcs',
    -- Warn when stock is at or below this. 0 = no warning.
    min_level         INTEGER      NOT NULL DEFAULT 0 CHECK (min_level >= 0),
    location          TEXT,
    note              TEXT,
    quantity_on_hand  INTEGER      NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0),
    -- Retired parts drop out of lists; their history stays.
    active            BOOLEAN      NOT NULL DEFAULT true,
    created_by        TEXT         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_mk_spare_part_no ON mk_spare_part (lower(part_no));

-- Every stock in and out. APPEND-ONLY: a mistake is corrected with an ADJUST, never by
-- editing a row. quantity_change is signed (+ in, - out); balance_after is the stock
-- after this movement, so the history reads as a running total.
CREATE TABLE mk_stock_movement (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    part_id          UUID         NOT NULL REFERENCES mk_spare_part(id),
    movement_type    TEXT         NOT NULL CHECK (movement_type IN ('IN', 'OUT', 'ADJUST')),
    quantity_change  INTEGER      NOT NULL CHECK (quantity_change <> 0),
    balance_after    INTEGER      NOT NULL CHECK (balance_after >= 0),
    -- Required for OUT and ADJUST: every stock out says why.
    reason           TEXT,
    -- Optional paperwork: PO, delivery note, invoice...
    reference        TEXT,
    -- The day it happened (may be earlier than the day it was recorded).
    moved_on         DATE         NOT NULL DEFAULT current_date,
    created_by       TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_mk_movement_direction CHECK (
        (movement_type = 'IN' AND quantity_change > 0)
        OR (movement_type = 'OUT' AND quantity_change < 0)
        OR movement_type = 'ADJUST'),
    CONSTRAINT ck_mk_movement_reason CHECK (
        movement_type = 'IN' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);
CREATE INDEX ix_mk_movement_part ON mk_stock_movement (part_id, moved_on DESC, created_at DESC);
CREATE INDEX ix_mk_movement_day ON mk_stock_movement (moved_on);

CREATE TRIGGER mk_stock_movement_append_only BEFORE UPDATE OR DELETE ON mk_stock_movement
    FOR EACH ROW EXECUTE FUNCTION re_append_only();

-- The PIN MK staff enter to view the stock. Stored as a BCrypt hash, never in plain text.
-- One active PIN at a time; setting a new one retires the old and ends every session.
CREATE TABLE mk_access_pin (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    pin_hash      TEXT         NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT true,
    created_by    TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_mk_access_pin_active ON mk_access_pin (active) WHERE active;

-- A view-only session handed out for a correct PIN. Only the SHA-256 of the token is
-- stored; the token itself lives in the viewer's httpOnly cookie.
CREATE TABLE mk_view_session (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash   TEXT         NOT NULL UNIQUE,
    pin_id       UUID         NOT NULL REFERENCES mk_access_pin(id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_mk_view_session_expiry ON mk_view_session (expires_at);
