-- =============================================================================
-- V15 - Telemetry Phase 2: LINE Messaging API recipient id on customer profiles
-- LINE Notify (the original target of line_notify_token) was permanently shut
-- down on 2025-03-31. Monthly report delivery now uses the LINE Messaging API,
-- whose push target is a recipient id captured by the n8n webhook when the
-- recipient interacts with the RAASPAL Official Account. The same column holds
-- a user id, group id, or room id interchangeably (LINE's push "to" accepts
-- any of them), so internal team tests can point it at a team LINE group.
-- =============================================================================

ALTER TABLE customer_profiles ADD COLUMN line_user_id VARCHAR(255);

COMMENT ON COLUMN customer_profiles.line_notify_token IS
    'DEPRECATED: LINE Notify was shut down 2025-03-31; use line_user_id with the LINE Messaging API.';
