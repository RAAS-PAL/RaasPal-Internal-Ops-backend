-- Production hardening for the partner API (/api/partner/v1).

-- Optional key expiry. NULL = never expires (every key issued before this
-- migration), so existing integrations keep working untouched. A key past its
-- expires_at is rejected by the auth filter exactly like a revoked one.
ALTER TABLE partner_api_keys ADD COLUMN expires_at TIMESTAMP;

-- Access audit: who fetched what, when. Broader than partner_api_keys.last_used_at
-- (a single "most recent" timestamp) — this keeps the individual requests, so a
-- partner's usage can be reviewed, disputes settled, and abuse spotted.
-- partner_id / api_key_id are NULL for requests that failed authentication, which
-- is deliberate: rejected attempts are exactly what you want a record of.
CREATE TABLE partner_api_access_logs (
    id           UUID PRIMARY KEY,
    partner_id   UUID REFERENCES partners(id),
    api_key_id   UUID REFERENCES partner_api_keys(id),
    method       VARCHAR(10)  NOT NULL,
    path         VARCHAR(500) NOT NULL,
    query_string VARCHAR(1000),
    status       INTEGER      NOT NULL,
    duration_ms  INTEGER      NOT NULL,
    client_ip    VARCHAR(64),
    requested_at TIMESTAMP    NOT NULL
);

-- Reviewing one partner's recent activity is the common read; the second index
-- supports pruning old rows by age.
CREATE INDEX idx_partner_access_logs_partner_time ON partner_api_access_logs (partner_id, requested_at DESC);
CREATE INDEX idx_partner_access_logs_time ON partner_api_access_logs (requested_at);
