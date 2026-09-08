-- =============================================================================
-- V41 - Not every row on a KPI board is a KPI case
--
-- The "Installation Tickets" board (3109668017) turned out to be the RE team's
-- job board: its "Job Type" column has 25 labels - Survey Site, Demo, Training,
-- Transport, MA, Meeting, QC, Unbox ... - and "Installation" is only one of them.
-- Counting every row as an installation gave 59 for Jan-Jun 2026 against the
-- deck's 23. The CM boards have the same shape in "Type of case" (request /
-- service / incident / parts shipping), which is the likely source of the deck's
-- 1,270 "KPI cases" out of 1,458.
--
-- So each board may name one column as its category, and list which values count.
-- The value is stored here so the KPI can filter on it without parsing the JSON
-- archive on every read. Rows outside the list are still synced and still
-- archived - they are the team's work, just not this KPI's - and the API reports
-- how many were left out, so a denominator never shrinks silently.
--
-- Additive: nullable, no rewrite, no lock; the deployed backend never reads it.
-- =============================================================================

ALTER TABLE case_ticket ADD COLUMN category TEXT;

COMMENT ON COLUMN case_ticket.category IS
    'Text of the board''s configured category column (Job Type, Type of case). '
    'Null when the board maps none. The KPI counts only rows whose category is in '
    'the board''s include-categories list, or every row when that list is empty.';
