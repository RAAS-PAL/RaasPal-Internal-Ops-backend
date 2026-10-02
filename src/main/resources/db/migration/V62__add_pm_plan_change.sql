-- =============================================================================
-- V62 - A record of every PM visit moved from the planner, and of every undo
--
-- The 52-week planner can now change a visit's Plan date on monday. monday's own
-- activity log shows the API token's owner as the author of every such change,
-- not the person who clicked, so who moved what, and from when to when, is kept
-- here instead. The planner lists these rows as "Recent moves" and undoes from them.
--
-- Append-only: an undo is a row of its own (action = 'UNDO') pointing at the move
-- it reverses, never an update of that move.
--
-- No foreign key to pm_visit: this is a log, and it should outlive the row it
-- describes. The monday ids are kept for that reason.
--
-- Additive only.
-- =============================================================================
CREATE TABLE pm_plan_change (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    pm_visit_id          UUID         NOT NULL,
    source_board_id      VARCHAR(32)  NOT NULL,
    source_item_id       VARCHAR(32)  NOT NULL,
    -- MOVE, or UNDO of an earlier move.
    action               VARCHAR(8)   NOT NULL DEFAULT 'MOVE',
    -- The move an UNDO reverses; null on a MOVE.
    undoes_change_id     UUID,
    -- Null when the visit had no plan date (before a move, or after an undo of one).
    old_plan_date        DATE,
    new_plan_date        DATE,
    -- The visit was completed on monday, and the person confirmed moving it anyway.
    confirmed_completed  BOOLEAN      NOT NULL DEFAULT false,
    changed_by           TEXT         NOT NULL,
    changed_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pm_plan_change_visit ON pm_plan_change (pm_visit_id, changed_at);
CREATE INDEX idx_pm_plan_change_changed_at ON pm_plan_change (changed_at);
CREATE INDEX idx_pm_plan_change_undoes ON pm_plan_change (undoes_change_id);
