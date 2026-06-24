-- In this MVP a customer is an internal record (the recipient of reports), not a
-- login account, so a CustomerProfile no longer requires a linked User. The
-- user_id column stays UNIQUE (a future customer login could link one) but
-- becomes nullable so the admin can add customers without creating users.
ALTER TABLE customer_profiles ALTER COLUMN user_id DROP NOT NULL;

-- Contact email used to deliver the monthly report (replaces the LINE path).
ALTER TABLE customer_profiles ADD COLUMN contact_email VARCHAR(255);
