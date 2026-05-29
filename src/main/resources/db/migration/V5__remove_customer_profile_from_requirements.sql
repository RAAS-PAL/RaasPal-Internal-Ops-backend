-- V5: Remove customer_profile_id from requirements.
-- CustomerProfile linking is not part of MVP scope.
-- The customer_profiles table is retained for future use.

DROP INDEX IF EXISTS idx_requirements_customer_profile_id;

ALTER TABLE requirements DROP COLUMN IF EXISTS customer_profile_id;
