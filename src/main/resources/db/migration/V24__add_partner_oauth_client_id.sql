-- OAuth 2.0 client credentials for the partner API.
--
-- Each API key becomes a client_id / client_secret pair: the secret is the
-- existing pk_... value (only its hash is stored), and this adds the public
-- identifier that goes alongside it at the token endpoint.
--
-- Deliberately NOT reusing key_prefix as the client_id: that column holds the
-- first 12 characters of the secret, so publishing it would leak part of the
-- private value.
--
-- The column is NULLABLE on purpose. Local development and production share one
-- database, so an older build that knows nothing about client_id must still be
-- able to insert keys while the new code rolls out. A later migration can
-- tighten this to NOT NULL once every deployment is writing it.
ALTER TABLE partner_api_keys ADD COLUMN client_id VARCHAR(64);

-- Backfill deterministically from the row's own primary key: guaranteed unique,
-- repeatable, and no randomness inside a migration.
-- 'cid_' rather than 'pk_': the secret already uses pk_, and a public identifier
-- that looks like a secret invites someone to treat it as one.
UPDATE partner_api_keys
SET client_id = 'cid_' || substr(replace(id::text, '-', ''), 1, 20)
WHERE client_id IS NULL;

ALTER TABLE partner_api_keys ADD CONSTRAINT uq_partner_api_keys_client_id UNIQUE (client_id);

-- The token endpoint looks a key up by client_id on every exchange.
CREATE INDEX idx_partner_api_keys_client_id ON partner_api_keys (client_id);
