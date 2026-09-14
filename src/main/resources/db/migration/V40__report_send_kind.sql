-- =============================================================================
-- V40 - What kind of email a report_sends row records
--
-- report_sends was the record of monthly BUNDLE deliveries and nothing else. The
-- Report preview tab's Send, which emails ONE robot's report, wrote no row - so
-- it was invisible in Delivery history, and the monthly run (which consults only
-- this table) would send that customer the bundle again.
--
-- `kind` says which it was. Existing rows are all bundles, hence the default.
-- `robot_serial` is only set for ROBOT_REPORT rows, so the history can say which
-- machine's report went out. The monthly run keeps skipping on BUNDLE rows only:
-- one robot's report by hand is not the month's deliverable.
-- =============================================================================

ALTER TABLE report_sends
    ADD COLUMN kind         VARCHAR(20)  NOT NULL DEFAULT 'BUNDLE',
    ADD COLUMN robot_serial VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_report_sends_customer_month_kind
    ON report_sends (customer_profile_id, report_month, kind, status);
