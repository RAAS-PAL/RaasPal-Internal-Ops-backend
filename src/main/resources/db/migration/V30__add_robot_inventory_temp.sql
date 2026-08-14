-- =============================================================================
-- V30 - Standalone robot stock record for RIMS
--
-- Deliberately unconnected. No foreign keys to robot_units, robots, or anything
-- else: this is what the inventory team types in, and nothing about it is allowed
-- to disturb the fleet record. robot_units carries 152 real machines with
-- telemetry, deployments and partner-API scoping hanging off them; entangling a
-- hand-kept warehouse list with it buys nothing today and risks a lot.
--
-- Because it is standalone, quantity can honestly be a NUMBER. On robot_units it
-- could not: a serial is a robot's identity across telemetry, monthly reports and
-- the partner API, so a bare count there would have nothing behind it. Here the
-- table makes no claim to be the fleet, so counting is the right shape.
--
-- ⚠️ The name is the user's, and it is NOT a Postgres TEMPORARY table — this is an
-- ordinary permanent table that survives the session. Postgres's own TEMP tables
-- vanish on disconnect; nothing here does.
-- =============================================================================

CREATE TABLE robot_inventory_temp (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    robot_type  VARCHAR(20)  NOT NULL,           -- CLEANING | DELIVERY | MOWING | SECURITY
    brand       VARCHAR(100) NOT NULL,
    model       VARCHAR(255) NOT NULL,

    -- Free text, because it is used for two different things that look the same on
    -- a shelf: a revision ("v1.3") and a configuration ("Roller Brush").
    version     VARCHAR(100),

    -- http(s) URL or a base64 data: URI. TEXT because an uploaded photo runs to
    -- hundreds of kilobytes — the same choice cm_reports makes for signatures,
    -- since Render's disk is ephemeral and a file written there vanishes on deploy.
    image_url   TEXT,

    -- A plain count. Only meaningful because this table is not the fleet record.
    quantity    INTEGER      NOT NULL DEFAULT 0 CHECK (quantity >= 0),

    -- What the count was before the last change to it, and when — a safety net for
    -- the commonest warehouse slip: typing 3 where 30 was meant, with no way to tell
    -- what the number used to be.
    --
    -- ⚠️ ONE step back, not a history. Edit twice and the original is gone. That is
    -- enough to undo a mistake noticed straight away, which is what it is for; a full
    -- trail of who changed what and when would be a movements table like
    -- stock_movements, not more columns here.
    --
    -- Written only when the quantity actually changes. Saving an edit to the location
    -- must not overwrite the backup with the current number, or an unrelated edit
    -- destroys the value worth keeping.
    previous_quantity    INTEGER,
    previous_quantity_at TIMESTAMP,

    -- IN_STOCK | DEMO only. Rented and sold robots are the fleet's business and
    -- have no row here at all.
    status      VARCHAR(20)  NOT NULL DEFAULT 'IN_STOCK',

    location    VARCHAR(128),
    note        TEXT,

    -- Who last touched it. Intentionally NOT a foreign key: this table is meant to
    -- stand alone, and an unresolvable id is a smaller problem than a constraint
    -- that couples it to the user table. Resolved for display when it matters.
    updated_by  UUID,

    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- One row per robot per status: three Phantas v1.3 in stock and one on demo are two
-- rows, but "three in stock" must never be two rows saying two and one.
--
-- Lowercased so "Gausium" and "GAUSIUM" collide, and COALESCE'd because a NULL
-- version would otherwise slip past — in Postgres NULLs are distinct, so without it
-- two version-less rows for the same model would both be allowed.
CREATE UNIQUE INDEX uq_robot_inventory_temp_identity
    ON robot_inventory_temp (
        lower(brand),
        lower(model),
        lower(coalesce(version, '')),
        status
    );

CREATE INDEX idx_robot_inventory_temp_status ON robot_inventory_temp (status);
CREATE INDEX idx_robot_inventory_temp_type   ON robot_inventory_temp (robot_type);
