-- Per-robot opt-outs from a customer's combined monthly report.
--
-- A customer like IFS has many robots, and in any given month some of them were
-- offline and have no activity at all. Their pages still render, as a wall of
-- zeros, which makes the combined report look broken rather than accurate. This
-- lets the customer success team drop those specific robots from that specific
-- month before sending.
--
-- A row here means "skip this robot for this customer, this month". Deleting the
-- row puts the robot straight back into the report, so the choice is fully
-- reversible and never touches the robot's telemetry — it only filters which
-- pages the bundle renders.
--
-- Scoped to (customer, month, robot) rather than to the robot alone: a robot that
-- was idle in July is usually back in service in August, so an exclusion must not
-- silently persist into later months.
--
-- Stored rather than applied at preview time because the monthly email links to
-- the public bundle page. If this lived only in the admin UI, the team would
-- approve one thing and the customer would open another.
CREATE TABLE customer_report_exclusions (
    id                  UUID PRIMARY KEY,
    customer_profile_id UUID NOT NULL REFERENCES customer_profiles(id) ON DELETE CASCADE,
    report_month        VARCHAR(7) NOT NULL,
    robot_unit_id       UUID NOT NULL REFERENCES robot_units(id) ON DELETE CASCADE,
    created_at          TIMESTAMP NOT NULL,
    -- Ticking the same robot off twice must not create a second row.
    CONSTRAINT uq_customer_report_exclusion UNIQUE (customer_profile_id, report_month, robot_unit_id)
);

-- Every read is "which robots are excluded for this customer this month".
CREATE INDEX idx_customer_report_exclusions_lookup
    ON customer_report_exclusions (customer_profile_id, report_month);
