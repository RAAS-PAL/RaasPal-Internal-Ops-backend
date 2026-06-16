-- =============================================================================
-- V11 - Robot Units & Deployments
-- Foundation for the telemetry/report features: a robot_unit is a physical
-- robot identified by its manufacturer serial number; a deployment links a
-- robot_unit to the customer_profile and site where it is installed.
-- =============================================================================

CREATE TABLE robot_units (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_number   VARCHAR(100) NOT NULL UNIQUE,
    brand           VARCHAR(100) NOT NULL,
    model           VARCHAR(255),
    name            VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE deployments (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    robot_unit_id        UUID         NOT NULL REFERENCES robot_units(id),
    customer_profile_id  UUID         NOT NULL REFERENCES customer_profiles(id),
    site                 VARCHAR(255),
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    deployed_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_robot_units_brand           ON robot_units(brand);
CREATE INDEX idx_deployments_robot_unit_id   ON deployments(robot_unit_id);
CREATE INDEX idx_deployments_customer_profile_id ON deployments(customer_profile_id);
CREATE INDEX idx_deployments_is_active       ON deployments(is_active);