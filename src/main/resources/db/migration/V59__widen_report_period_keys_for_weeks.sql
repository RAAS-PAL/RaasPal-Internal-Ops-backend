-- Weekly performance reports can now be shared and emailed, so a robot's report
-- link and its send history must hold an ISO week key ("2026-W38", 8 chars) as
-- well as a month ("2026-08", 7 chars).
--
-- The columns keep their names. They now hold a *period key*, and the key's own
-- shape says which kind it is, so no separate period-type column is needed.
--
-- Widening a VARCHAR only rewrites the column definition in Postgres, not the
-- table, and every existing 7-character value stays valid. The currently deployed
-- code keeps working against the wider columns, so this is safe to apply ahead of
-- the code that uses it.
--
-- customer_report_links is deliberately untouched: the customer bundle stays
-- monthly-only.
ALTER TABLE report_links ALTER COLUMN report_month TYPE VARCHAR(8);
ALTER TABLE report_sends ALTER COLUMN report_month TYPE VARCHAR(8);
