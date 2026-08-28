-- Robot lifecycle and packaging on the warehouse shelf.
--
-- Two things the stock record could not express: where a robot stands in its
-- lifecycle beyond "held or on demo", and whether it is still in its carton.
--
-- Note what is NOT here: any rewrite of existing rows. IN_STOCK and DEMO already
-- carry exactly the meaning asked for and are simply labelled "New Stock" and
-- "Demo Unit" in the interface. The two genuinely new states, UNDER_REPAIR and
-- RETURNED_FROM_CUSTOMER, are added to the enum in Java. So this migration only
-- makes room; every existing row stays valid and readable by the currently deployed
-- code, which matters because local development and production share one database.

-- RETURNED_FROM_CUSTOMER is 22 characters and the column holds 20, so the value
-- could not be stored at all without this. Widening a varchar in Postgres rewrites
-- no rows and takes no lasting lock.
ALTER TABLE robot_inventory_temp ALTER COLUMN status TYPE VARCHAR(32);

-- Deliberately NOT part of uq_robot_inventory_temp_identity, unlike status.
--
-- Packaging describes the state of a shelf, not the identity of what is on it. Adding
-- it to the key would mean unboxing a single unit splits the row in two, and every
-- entry that predates this column would need a value invented for it before the index
-- could be rebuilt.
--
-- The accepted consequence: a row holding four units records one packaging value for
-- all four. A shelf with two boxed and two unboxed cannot be told apart. If that turns
-- out to matter, the fix is to add packaging to the identity index and split the rows
-- then, with real values to split on.
ALTER TABLE robot_inventory_temp ADD COLUMN packaging VARCHAR(16);

COMMENT ON COLUMN robot_inventory_temp.packaging IS
    'BOX | UNBOX. Null where nobody has recorded it yet, which is every row predating this column.';
