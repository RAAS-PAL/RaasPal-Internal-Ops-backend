ALTER TABLE recommendation_items
    ADD COLUMN IF NOT EXISTS customer_summary TEXT;
