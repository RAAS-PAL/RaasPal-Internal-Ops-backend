-- =============================================================================
-- V67 - AOTGA's list as it stood each day, so it can be read by day, week and
--       month like the other pending sheets
--
-- AOT's sheet only says how things are now, and the tracker builds its list
-- from that. Each day's last state is kept here: today's row is rewritten with
-- every refresh (and whenever someone opens the AOT tab), so once the day is
-- over it holds what the list showed at its end. A week or a month is read from
-- its last day, as the other sheets are.
--
-- view_json: the tracker's view - cases not yet claimed and those claimed
--            lately - as the console receives it.
--
-- Additive only. History starts the day this runs.
-- =============================================================================
CREATE TABLE aotga_snapshot (
    run_date       DATE         PRIMARY KEY,
    spreadsheet_id TEXT,
    view_json      TEXT         NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
