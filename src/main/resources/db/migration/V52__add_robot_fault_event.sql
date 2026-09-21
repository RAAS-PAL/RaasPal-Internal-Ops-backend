-- Robot fault history, recorded from now on.
--
-- AutoXing's API reports only the faults a robot has *right now*; there is no history
-- endpoint, so a fault that cleared yesterday is gone unless something wrote it down
-- while it was active. A poller reads the whole fleet every few seconds and writes one
-- row per occurrence: opened when a fault first appears, closed when it disappears.
-- Nothing is written while a fault simply stays active.
--
-- kind:
--   ERROR           an error code the robot reports (error_code set), e.g. 2008
--                   "Wheel is major slipping", 9504 "V2X firmware version too low"
--   EMERGENCY_STOP  the E-stop is pressed (error_code null)
--   OFFLINE         the robot stopped reporting to the cloud (error_code null)
--
-- Times are when the poller *noticed*, to within one poll interval. A backend restart
-- leaves open rows open; the next poll closes whatever has since cleared, so a fault
-- that cleared during the downtime is closed at the time it was seen gone.
--
-- Additive only: a new table, nothing existing changes.

CREATE TABLE robot_fault_event (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brand          VARCHAR(20)  NOT NULL,
    robot_id       TEXT         NOT NULL,
    business_id    TEXT,
    kind           VARCHAR(20)  NOT NULL,
    error_code     INTEGER,
    error_level    INTEGER,
    message        TEXT,
    first_seen_at  TIMESTAMPTZ  NOT NULL,
    cleared_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_robot_fault_event_kind CHECK (kind IN ('ERROR', 'EMERGENCY_STOP', 'OFFLINE')),
    CONSTRAINT ck_robot_fault_event_code CHECK ((kind = 'ERROR') = (error_code IS NOT NULL))
);

-- The report's query: one robot's faults overlapping a period.
CREATE INDEX ix_robot_fault_event_robot_time
    ON robot_fault_event (brand, robot_id, first_seen_at);

-- At most one open row per robot and fault. A second poller (a local backend pointed at
-- this database by mistake) cannot open a duplicate; its insert fails instead.
CREATE UNIQUE INDEX uq_robot_fault_event_open
    ON robot_fault_event (brand, robot_id, kind, COALESCE(error_code, -1))
    WHERE cleared_at IS NULL;

COMMENT ON TABLE robot_fault_event IS
    'One row per robot fault occurrence (error code, E-stop, offline), from the fleet poller. cleared_at null = still active.';
