-- Follow-up tracking for the monthly "robots with no data" check.
--
-- At the end of each month the customer success team looks at every robot that
-- logged nothing and contacts the customer to find out why -- nearly always the
-- robot was offline. The list itself is computed on request from robot_task_reports
-- (a stored flag would go stale the moment a backfill landed); this table holds what
-- was done about each entry, keyed on the robot and the month, so the list reads as
-- a worklist rather than a bare report: who has been contacted, what they said, and
-- what it turned out to be.
CREATE TABLE zero_data_followups (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    robot_unit_id  UUID NOT NULL REFERENCES robot_units(id) ON DELETE CASCADE,
    report_month   VARCHAR(7) NOT NULL,
    status         VARCHAR(20) NOT NULL,           -- TO_CONTACT | CONTACTED | RESOLVED
    outcome        VARCHAR(30),                    -- ROBOT_OFFLINE | IN_STORAGE | CONTRACT_ENDED | REGISTRATION_ERROR | SYNC_PROBLEM | OTHER
    note           TEXT,
    updated_by     TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_zero_data_followup UNIQUE (robot_unit_id, report_month)
);
