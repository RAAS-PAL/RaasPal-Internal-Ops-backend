-- When the "contract ends within 30 days" alert was sent for this deployment.
--
-- The daily alert selects deployments whose contract_end_date falls within the
-- window and that have not been alerted yet, then stamps them here. Selecting on
-- "not yet alerted" rather than "ends exactly N days from today" means a day the
-- scheduler did not run is caught up the next morning instead of missed for good.
-- Cleared when the end date is changed, so an extension re-arms the alert.
ALTER TABLE deployments ADD COLUMN contract_expiry_alerted_at TIMESTAMPTZ;
