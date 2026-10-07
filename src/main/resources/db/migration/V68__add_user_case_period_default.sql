-- The period each person's pending-case tabs open on when the link names none:
-- DAILY, WEEKLY, MONTHLY or ALL (all time). NULL is the default, MONTHLY.
ALTER TABLE users ADD COLUMN case_period_default VARCHAR(8);
