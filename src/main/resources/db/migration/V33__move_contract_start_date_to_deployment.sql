-- Move the contract start date from the customer to the deployment.
--
-- V32 put it on customer_profiles, which was the wrong grain: one customer rents
-- or buys different robots at different times. IFS alone has robots across many
-- sites, each with its own start date, so a single date per customer cannot clip
-- their reports correctly.
--
-- A deployment is exactly the robot-to-customer link, so it is where "when did
-- this robot start working for this customer" belongs.
--
-- Safe to drop the old column: it shipped only hours earlier and was verified
-- empty across all 67 customers before this migration was written, so no dates
-- are lost. (Had any been set, they would need copying onto each customer's
-- active deployments first.)
--
-- Note deployments.deployed_at is NOT a substitute — it is set to now() when a
-- robot is registered, so it records when someone typed the robot into the
-- system, not when the contract began. Existing values are import timestamps.
ALTER TABLE deployments ADD COLUMN contract_start_date DATE;

ALTER TABLE customer_profiles DROP COLUMN contract_start_date;
