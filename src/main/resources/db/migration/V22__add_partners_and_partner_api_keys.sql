-- Distributor / service partners (e.g. PCS): companies that service a subset of
-- our robots for their own end-customers and pull those robots' task data via
-- the read-only partner API (/api/partner/v1).
CREATE TABLE partners (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL
);

-- API keys for partner authentication. The plaintext key is shown ONCE at
-- creation; only its SHA-256 hash is stored. key_prefix (first characters of
-- the key) is used for indexed lookup and for display ("sk_pcs_ab12…").
-- Revocation = is_active false + revoked_at set; rotation = new row.
CREATE TABLE partner_api_keys (
    id           UUID PRIMARY KEY,
    partner_id   UUID NOT NULL REFERENCES partners(id),
    key_hash     VARCHAR(64) NOT NULL UNIQUE,
    key_prefix   VARCHAR(12) NOT NULL,
    label        VARCHAR(255),
    is_active    BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    revoked_at   TIMESTAMP
);

CREATE INDEX idx_partner_api_keys_prefix ON partner_api_keys (key_prefix);

-- Which partner services a deployment's robot. NULL = RAASPAL-direct (no
-- partner); nullable so existing rows are untouched. The partner API scopes
-- every query through this column.
ALTER TABLE deployments ADD COLUMN partner_id UUID REFERENCES partners(id);
CREATE INDEX idx_deployments_partner ON deployments (partner_id);
