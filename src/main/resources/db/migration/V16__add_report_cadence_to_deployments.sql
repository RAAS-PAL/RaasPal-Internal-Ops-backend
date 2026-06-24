-- Per-robot report cadence. Each deployment (a robot installed at a customer
-- site) carries its own schedule so one customer can be weekly and another
-- monthly. Values map to the ReportCadence enum: MONTHLY | WEEKLY | OFF.
ALTER TABLE deployments
    ADD COLUMN report_cadence VARCHAR(20) NOT NULL DEFAULT 'MONTHLY';
