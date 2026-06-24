-- Customers are tracked by branch rather than a free-form postal address.
ALTER TABLE customer_profiles RENAME COLUMN address TO branch;
