-- Tracks every email delivery attempt for a customer's monthly report bundle.
-- Used for idempotency (skip if already SENT), delivery history, and audit.
CREATE TABLE report_sends (
    id                  UUID PRIMARY KEY,
    customer_profile_id UUID NOT NULL REFERENCES customer_profiles(id),
    report_month        VARCHAR(7) NOT NULL,
    status              VARCHAR(16) NOT NULL,   -- SENT | FAILED | SKIPPED
    recipient_email     VARCHAR(255),
    error_message       TEXT,
    sent_at             TIMESTAMP NOT NULL
);

CREATE INDEX idx_report_sends_customer_month
    ON report_sends (customer_profile_id, report_month);
