-- =============================================================================
-- V9 - CVTE C3 Online/Offline Status Tracking
-- Stores devices synced from the Kava Open Gateway API. Status-only for now;
-- location/map/task/alert data are intentionally out of scope (see CLAUDE.md).
-- =============================================================================

CREATE TABLE cvte_devices (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           BIGINT         NOT NULL UNIQUE,
    factory_sn          VARCHAR(100)   NOT NULL UNIQUE,
    device_name         VARCHAR(255),
    org_code            VARCHAR(100),
    online_status       BOOLEAN,
    running_state       VARCHAR(50),
    battery_percentage  DOUBLE PRECISION,
    last_checked_at     TIMESTAMP,
    last_message        VARCHAR(500),
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cvte_devices_org_code ON cvte_devices(org_code);
CREATE INDEX idx_cvte_devices_device_name ON cvte_devices(device_name);
