-- What the customer success team has done about a contract that is ending: have
-- they called the customer yet, and what did the customer say.
--
-- On the deployment itself, next to contract_expiry_alerted_at, because it is about
-- the same thing - this deployment's current contract term - and resets the same way:
-- when the end date changes (a renewal recorded in Tools -> Robots), the follow-up
-- goes back to "not contacted" for the new term, so the next renewal is chased again.
-- Null status = not contacted yet.

ALTER TABLE deployments
    ADD COLUMN renewal_status     VARCHAR(20),
    ADD COLUMN renewal_note       TEXT,
    ADD COLUMN renewal_updated_by VARCHAR(255),
    ADD COLUMN renewal_updated_at TIMESTAMPTZ;

COMMENT ON COLUMN deployments.renewal_status IS
    'CS follow-up on the current contract term: CONTACTED | WILL_RENEW | WILL_NOT_RENEW. Null = not contacted yet. Reset when the end date changes.';
COMMENT ON COLUMN deployments.renewal_note IS
    'What the customer said, in the CS team''s words.';
