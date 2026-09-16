-- =============================================================================
-- V47 - A ticket may not say which kind of robot it is about
--
-- V45 made case_ticket.service_line NOT NULL on the assumption that the board a
-- ticket came from determines it: Cleaning Tickets and Delivery Tickets do.
--
-- The Installation Tickets board (3109668017) does not. It carries both robot
-- types and has no cleaning/delivery column at all - the nearest are "Job Type"
-- and "Robot  Models", which name models rather than families. So the line is
-- inferred from the model, and an unrecognised or blank model leaves it genuinely
-- unknown.
--
-- Unknown must be storable. The alternative - defaulting to one of the two - would
-- silently inflate that side of every split on a board slide, and the error would
-- be invisible precisely because it looks like data. A null here surfaces instead:
-- the fleet total still counts the ticket, the cleaning/delivery split does not,
-- and the API reports how many are unclassified so nobody reads the split as
-- complete when it is not.
--
-- Additive and safe: widening a NOT NULL to nullable rewrites no rows, takes no
-- lasting lock, and every existing row keeps its value. The deployed backend
-- never writes a null here, so it is unaffected.
-- =============================================================================

ALTER TABLE case_ticket ALTER COLUMN service_line DROP NOT NULL;

COMMENT ON COLUMN case_ticket.service_line IS
    'CLEANING | DELIVERY, or null when the source board does not say which and the '
    'robot model did not resolve it. Null rows count in fleet totals but not in the split.';
