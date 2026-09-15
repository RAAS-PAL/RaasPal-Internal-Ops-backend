-- What happened the last time each robot was synced.
--
-- Until now a robot whose nightly sync failed every night was indistinguishable, on
-- the monthly report and on the "no data" list, from a robot that sat idle: the sync
-- loop caught the failure, wrote a log line and moved on. With the customer success
-- team contacting customers off that list, "your robot was offline all month" and
-- "sorry, that was our sync" have to be told apart before the call, not after.
--
-- Three columns the loop already knows at the moment it catches the exception.
ALTER TABLE robot_units ADD COLUMN last_sync_attempt_at TIMESTAMPTZ;
ALTER TABLE robot_units ADD COLUMN last_sync_success_at TIMESTAMPTZ;
ALTER TABLE robot_units ADD COLUMN last_sync_error      TEXT;
