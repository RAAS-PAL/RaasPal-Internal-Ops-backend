-- Supports the partner API's paginated task-report queries.
--
-- All three are "WHERE robot_unit_id = ? ... ORDER BY start_time DESC" with a LIMIT:
-- the unfiltered listing, the month filter, and the from/to day range. The existing
-- idx_robot_task_reports_robot_unit_month covers the filtering but says nothing about
-- start_time, so Postgres reads every row for the robot and sorts it before applying
-- the limit. With start_time in the index the rows arrive already ordered and the scan
-- stops at the page boundary. The range filter benefits twice, since start_time is
-- both the predicate and the sort key.
--
-- Preventive rather than a fix for something measured: at ~34,000 rows over ~80 robots
-- the sort costs little today. It is added now because the table only grows (roughly
-- 270,000 rows a year) and because a paginated endpoint ordered by a column wants the
-- index from the start, not after a partner reports slow pages.
--
-- The existing (robot_unit_id, report_month) index is left in place; it still serves
-- the monthly report aggregation, which does not order by start_time.

CREATE INDEX idx_robot_task_reports_robot_unit_start
    ON robot_task_reports (robot_unit_id, start_time DESC);
