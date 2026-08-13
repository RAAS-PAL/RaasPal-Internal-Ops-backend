-- =============================================================================
-- V28 - Inventory (RIMS) + robot unit lifecycle
--
-- Two independent concerns, one migration:
--
--   1. robot_units becomes an ASSET REGISTER, not just a telemetry anchor.
--      V11 built it as "a physical robot installed at a customer site" - there
--      was no way to represent a robot sitting in the warehouse. RIMS needs
--      exactly that, so a lifecycle status is added.
--
--   2. inventory_items / stock_movements cover FUNGIBLE stock (brushes,
--      filters, batteries) - counted, not serialised. Whole robots are NOT
--      inventory_items: a robot has a serial, a deployment and telemetry
--      history, so it stays in robot_units. Two kinds of stock, two mechanisms.
-- =============================================================================


-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Robot unit lifecycle
-- ─────────────────────────────────────────────────────────────────────────────

-- IN_STOCK | RENT | SOLD
--
-- SOLD robots deliberately KEEP their active deployment: RAASPAL still monitors
-- them and sends monthly reports after ownership transfers. So "sold" is a
-- commercial fact, not a location - which is why RIMS can filter on the single
-- condition status = 'IN_STOCK' and correctly exclude both rented and sold units.
ALTER TABLE robot_units ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'IN_STOCK';

-- Hardware revision of THIS unit (V1.0, V1.1, V1.3...). Per-unit, not per-model:
-- two Phantas can be different revisions. Kept separate from the model so one
-- catalogue row serves every revision that shares a datasheet.
ALTER TABLE robot_units ADD COLUMN version VARCHAR(50);

-- CLEANING | DELIVERY | MOWING | SECURITY (common.enums.RobotType).
-- Denormalised onto the unit rather than read through robot_id, because
-- robot_id is nullable - a unit whose model is not yet in the catalogue still
-- needs a type for RIMS filtering.
ALTER TABLE robot_units ADD COLUMN robot_type VARCHAR(20);

-- Link to the catalogue model. NULLABLE on purpose: 59 of the 152 existing units
-- carry a free-text model with no catalogue row yet. The FK - not a text match -
-- is what normalises "Phantas V1.1" and "Phantas" onto one model, so the raw
-- brand/model columns stay untouched as provenance from the source system.
ALTER TABLE robot_units ADD COLUMN robot_id UUID REFERENCES robots(id);

-- Where the unit physically is, in our own premises - in practice "Warehouse"
-- or "Office", though free text so it can carry more detail. NULLABLE: only
-- meaningful while status = 'IN_STOCK', and null once rented or sold.
--
-- Free text rather than a constrained value set, by choice. The cost is that
-- "Warehouse" / "warehouse" / "WH" are all valid to the database, so the UI
-- should offer a dropdown rather than a text input - otherwise grouping by
-- location degrades as soon as two people type it differently.
--
-- Note this is distinct from deployments.site, which is the CUSTOMER's premises.
ALTER TABLE robot_units ADD COLUMN location VARCHAR(128);


-- Backfill: everything currently at a customer becomes RENT.
--
-- The database CANNOT distinguish rented from sold - deployments only records
-- that a placement is active. RENT is the safe default (RaaS is the core model);
-- the sold units must be corrected manually afterwards with the serial list:
--   UPDATE robot_units SET status = 'SOLD' WHERE serial_number IN (...);
UPDATE robot_units ru SET status = 'RENT'
WHERE EXISTS (SELECT 1 FROM deployments d
              WHERE d.robot_unit_id = ru.id AND d.is_active);

-- Every unit in the fleet today is a Gausium cleaning robot.
UPDATE robot_units SET robot_type = 'CLEANING' WHERE robot_type IS NULL;
ALTER TABLE robot_units ALTER COLUMN robot_type SET NOT NULL;

-- Extract the revision out of the raw model text ("Phantas V1.1" -> "V1.1").
-- The capture group is deliberate: substring(... from ...) returns the first
-- parenthesised subexpression when one exists, so wrapping the whole pattern
-- returns the full match rather than a fragment.
UPDATE robot_units
SET version = substring(model from '([Vv][0-9.]+)')
WHERE model ~ '[Vv][0-9]';

-- Link the units whose model matches a catalogue row exactly.
UPDATE robot_units ru SET robot_id = r.id
FROM robots r
WHERE lower(trim(r.brand)) = lower(trim(ru.brand))
  AND lower(trim(r.model)) = lower(trim(ru.model))
  AND ru.robot_id IS NULL;

-- Every Phantas revision resolves to the one Phantas catalogue row.
UPDATE robot_units ru SET robot_id = r.id
FROM robots r
WHERE lower(trim(r.brand)) = 'gausium' AND lower(trim(r.model)) = 'phantas'
  AND lower(trim(ru.brand)) = 'gausium' AND ru.model ~* '^phantas'
  AND ru.robot_id IS NULL;

-- NOTE: units left with robot_id IS NULL are expected. The datasheet splits
-- Omnie and M50 into Disc Brush / Roller Brush variants with genuinely
-- different specs, but the fleet records them as plain "Omnie" (73 units) and
-- "M50" (16 units). Which physical unit is which is a warehouse question no
-- migration can answer - re-point them once the catalogue is filled in V29.

CREATE INDEX idx_robot_units_status   ON robot_units(status);
CREATE INDEX idx_robot_units_robot_id ON robot_units(robot_id);


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Fungible stock: parts and consumables
-- ─────────────────────────────────────────────────────────────────────────────

-- Internal SKUs are auto-generated when the admin does not supply one, so every
-- item always has a stable searchable code even when the supplier gives none.
CREATE SEQUENCE inventory_item_sku_seq START 1;

CREATE TABLE inventory_items (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Two different numbers. sku is OURS and always present (generated as
    -- INV-000001 if left blank). supplier_part_no is the manufacturer's and is
    -- optional - not every part has one, and it is deliberately NOT unique:
    -- the same part may be bought from two suppliers under different codes.
    sku               VARCHAR(64)  NOT NULL UNIQUE,
    supplier_part_no  VARCHAR(64),
    barcode           VARCHAR(64),

    name              VARCHAR(255) NOT NULL,
    category          VARCHAR(64)  NOT NULL,   -- BRUSH | FILTER | BATTERY | SQUEEGEE | ...

    -- Which robot MODEL this part fits. NULL = universal (detergent, cloths).
    -- Points at the model, not a unit: every Phantas takes the same brush.
    -- A part fitting several models needs a join table; deferred until one does.
    robot_id          UUID         REFERENCES robots(id),

    unit_of_measure   VARCHAR(16)  NOT NULL DEFAULT 'EA',   -- EA | L | M | BOX | SET

    -- CACHED balance. The ledger in stock_movements is the source of truth; this
    -- exists so the stock list renders without a GROUP BY over the whole ledger.
    -- Written ONLY by the movement service, inside the same transaction that
    -- appends the movement. Nothing else in the codebase may touch it.
    quantity_on_hand  INTEGER      NOT NULL DEFAULT 0,

    -- Alert threshold and purchase size are separate decisions: "tell me at 10"
    -- vs "then buy 50". Per item, so a costly battery can sit at 2.
    reorder_point     INTEGER      NOT NULL DEFAULT 10,
    reorder_quantity  INTEGER      NOT NULL DEFAULT 0,

    -- Last known cost. Deliberately NOT moving-average or FIFO - good enough for
    -- a stock valuation figure, not accounting-grade.
    unit_cost         DECIMAL(12,2),

    -- Where the stock physically sits, e.g. "Warehouse - Rack A-3" or "Office".
    -- Free text and nullable, matching robot_units.location.
    --
    -- NOTE this assumes a part lives at ONE place. If the same SKU is genuinely
    -- split across both buildings (say fast-moving consumables kept at the office,
    -- bulk at the warehouse), one column cannot express it and quantity_on_hand
    -- has to become a per-location table instead - which also drags in TRANSFER
    -- movements, per-location reorder points, and per-location vs aggregate
    -- alerting. Deliberately deferred until a real part needs it.
    location          VARCHAR(128),

    -- Soft delete: an item with movement history cannot be removed (FK), and the
    -- audit trail should survive anyway. Discontinued parts drop out of lists.
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_items_robot_id      ON inventory_items(robot_id);
CREATE INDEX idx_inventory_items_category      ON inventory_items(category);
CREATE INDEX idx_inventory_items_supplier_part ON inventory_items(supplier_part_no);
-- Drives the dashboard alert: WHERE quantity_on_hand <= reorder_point
CREATE INDEX idx_inventory_items_low_stock     ON inventory_items(quantity_on_hand, reorder_point);


-- The ledger. APPEND-ONLY: never UPDATE, never DELETE. A mistake is corrected by
-- recording a compensating ADJUSTMENT, which is what makes this an audit trail
-- rather than a log file. Note the absence of updated_at - rows never change.
CREATE TABLE stock_movements (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_item_id UUID         NOT NULL REFERENCES inventory_items(id),

    movement_type     VARCHAR(20)  NOT NULL,   -- RECEIPT | ISSUE | ADJUSTMENT | RETURN

    -- SIGNED: +50 received, -2 issued. One signed column means the balance is
    -- simply SUM(quantity_change); separate in/out columns could disagree.
    quantity_change   INTEGER      NOT NULL,

    -- Stock level after this event. Derivable, but stored so the history screen
    -- shows a running total without re-summing, and so a mismatch against the
    -- running sum exposes any write that bypassed the movement service.
    balance_after     INTEGER      NOT NULL,

    -- Which physical robot consumed the part. NULL for a RECEIPT from a supplier.
    -- Answers "how many brushes has this serial used this year?" - intelligence
    -- that cannot be reconstructed later if it is not captured now.
    robot_unit_id     UUID         REFERENCES robot_units(id),

    note              TEXT,
    created_by        UUID         REFERENCES users(id),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stock_movements_item_time  ON stock_movements(inventory_item_id, created_at DESC);
CREATE INDEX idx_stock_movements_robot_unit ON stock_movements(robot_unit_id);
