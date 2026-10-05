-- V63 is reserved by feat/aot-sheet-colour. Coordinate ordering before merge/deploy.
ALTER TABLE users ADD COLUMN kc_role VARCHAR(10)
    CHECK (kc_role IN ('VIEWER', 'EDITOR', 'ADMIN'));

-- Fail rather than silently merge any pre-existing case-variant accounts.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

-- One durable, locked slot per normalized email. Resends replace the code and ticket.
-- The send window is shared across SIGNUP/RESET and survives process restarts.
CREATE TABLE email_codes (
    email VARCHAR(255) PRIMARY KEY,
    purpose VARCHAR(6) NOT NULL CHECK (purpose IN ('SIGNUP', 'RESET')),
    code_hash VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    tries INTEGER NOT NULL DEFAULT 0 CHECK (tries BETWEEN 0 AND 5),
    used_at TIMESTAMP WITH TIME ZONE,
    window_started_at TIMESTAMP WITH TIME ZONE,
    send_count INTEGER NOT NULL DEFAULT 0,
    ticket_hash VARCHAR(64),
    ticket_expires_at TIMESTAMP WITH TIME ZONE,
    ticket_used_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_email_codes_email_purpose ON email_codes (email, purpose);
CREATE UNIQUE INDEX uq_email_codes_ticket_hash ON email_codes (ticket_hash);
