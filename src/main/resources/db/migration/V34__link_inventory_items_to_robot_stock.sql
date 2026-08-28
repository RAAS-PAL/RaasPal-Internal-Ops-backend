-- Which robots a spare part fits.
--
-- A join table, because the relationship is genuinely many-to-many: one filter
-- fits both the M50 and the M75, and one robot takes many parts. V28's single
-- inventory_items.robot_id could not say that, and its own comment already
-- flagged the join table as the known next step ("deferred until one does").
--
-- It references robot_inventory_temp — the RIMS warehouse list — and NOT the
-- robots catalogue, deliberately. Measured before this was written: only 14 of
-- the 92 robots the warehouse holds exist in the catalogue (15%). The other 78
-- (Autoxing, AIRROBO, CVTE, most Gausium variants) are models RAASPAL stocks
-- but will never put in a customer proposal, so linking parts to the catalogue
-- would have blocked the feature behind 78 spec-heavy catalogue entries nobody
-- needs. Parts belong to what the warehouse actually stocks.
--
-- (Yes, that table is still named "_temp". Renaming it was considered and
-- deliberately deferred — the data in it is live and the rename is cosmetic.)
CREATE TABLE inventory_item_robots (
    inventory_item_id UUID NOT NULL REFERENCES inventory_items(id)      ON DELETE CASCADE,
    robot_stock_id    UUID NOT NULL REFERENCES robot_inventory_temp(id) ON DELETE CASCADE,
    PRIMARY KEY (inventory_item_id, robot_stock_id)
);

-- The robot detail page asks "which parts fit this robot" — the reverse of the
-- primary key's column order, so it needs its own index.
CREATE INDEX idx_inventory_item_robots_robot ON inventory_item_robots (robot_stock_id);

-- The single-model column this replaces. Verified empty in production before
-- writing this (inventory_items has 0 rows), so nothing is migrated. Leaving it
-- would give two contradictory ways to record the same fact.
ALTER TABLE inventory_items DROP COLUMN robot_id;
