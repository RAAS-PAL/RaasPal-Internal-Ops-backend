-- =============================================================================
-- V65 - A Google Sheet case source can tell closure by a cell's colour
--
-- The AOT team marks a case whose old part has come back by highlighting its row
-- blue. The values alone cannot tell that row from one still in progress; the
-- colour can.
--
-- colour_header:    the column whose cell colour is read. Null means the status
--                   column, which is highlighted with the rest of the row.
-- closed_colours:   comma-separated #rrggbb values that mean the row is done on
--                   the sheet, chosen in the console from the colours the sheet
--                   actually uses.
-- colour_labels:    JSON, names the team gives other colours, shown on the
--                   tracker's rows in that colour: [{"colour":"#ffff00","label":"..."}].
--                   Only RAASPAL's reading of the colour; nothing is written back.
-- open_date_header: the column the issue date is read from. Null means the
--                   default, "Issue Date".
--
-- Additive only.
-- =============================================================================
ALTER TABLE case_source_sheet
    ADD COLUMN colour_header    TEXT,
    ADD COLUMN closed_colours   TEXT,
    ADD COLUMN colour_labels    TEXT,
    ADD COLUMN open_date_header TEXT;
