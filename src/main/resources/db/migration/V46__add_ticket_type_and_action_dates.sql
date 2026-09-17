-- =============================================================================
-- V46 - Installation tickets, and the dates the RE KPI formulas actually use
--
-- V45 assumed one kind of ticket (CM) and that closure came from a close date.
-- Neither holds. The RE team's formulas, given 2026-09-08:
--
--   1st Time Install - from the installation ticket's TimeLine (the LATER date),
--                      look forward 30 days; any CM naming the same serial in
--                      that window means the installation scores 0.
--   First Time Fix   - after a CM, another CM naming the same serial within
--                      14 days scores 0.
--   SLA              - the case was checked within 7 days of being reported.
--
-- So a third board (Installation) joins the two CM boards, and "checked" is the
-- board's RE Action date, not a close date - neither ticket board has a close
-- date column at all, which is why V45's close_date is almost always null.
--
-- Additive only, and every column is nullable or defaulted, so the currently
-- deployed backend keeps reading these tables unchanged. V45 has not reached
-- production yet; it is left untouched rather than edited, because a committed
-- migration is checksummed and editing one breaks the next start with no local
-- symptom.
-- =============================================================================

-- INSTALLATION | CM. Defaulted so every row V45 already wrote stays valid: at the
-- time only CM boards were synced.
ALTER TABLE kpi_case_ticket ADD COLUMN ticket_type VARCHAR(16) NOT NULL DEFAULT 'CM';

-- When the RE team first acted on the case. This is the SLA clock's second hand:
-- SLA is action_date - open_date <= the board's sla_days, NOT open-to-close.
-- Null means nobody has recorded an action, which is reported as "unknown" rather
-- than counted as a breach - an unrecorded action and a late action are different
-- claims, and only one of them is evidence.
ALTER TABLE kpi_case_ticket ADD COLUMN action_date DATE;

-- Installation tickets only: the later end of the TimeLine column, i.e. when the
-- installation finished. The 30-day first-time-install window is measured from
-- here. Kept separate from open_date so an installation's start and finish are
-- not silently conflated.
ALTER TABLE kpi_case_ticket ADD COLUMN install_date DATE;

COMMENT ON COLUMN kpi_case_ticket.ticket_type IS 'INSTALLATION | CM. Which board family the row came from.';
COMMENT ON COLUMN kpi_case_ticket.action_date IS 'Board "RE Action" date. SLA = action_date - open_date <= sla_days.';
COMMENT ON COLUMN kpi_case_ticket.install_date IS 'Installation tickets: later end of the TimeLine column.';

CREATE INDEX idx_kpi_case_ticket_type_install ON kpi_case_ticket (ticket_type, install_date);
CREATE INDEX idx_kpi_case_ticket_type_open    ON kpi_case_ticket (ticket_type, open_date);


-- -----------------------------------------------------------------------------
-- A sync run covers a BOARD, and a board need not be one service line.
--
-- The installation board carries both robot types on one board and says which in
-- a column, so there is no single line to stamp on its run row. V45 made this
-- column NOT NULL, which would have failed every installation sync outright.
-- Nullable now, with the board's ticket type recorded instead - that is a
-- property of the board and always known.
-- -----------------------------------------------------------------------------
ALTER TABLE kpi_case_ticket_sync_run ALTER COLUMN service_line DROP NOT NULL;
ALTER TABLE kpi_case_ticket_sync_run ADD COLUMN ticket_type VARCHAR(16) NOT NULL DEFAULT 'CM';

COMMENT ON COLUMN kpi_case_ticket_sync_run.service_line IS
    'Null when the board carries both lines and names the deciding column instead.';
