-- =============================================================================
-- V12 - Telemetry: Robot Task Reports & Gausium OAuth Tokens
-- Stores per-task cleaning reports synced from brand telemetry APIs
-- (starting with Gausium), plus the rotating OAuth tokens needed to call the
-- Gausium API. Brand-specific columns are nullable so new brands can reuse
-- this same table.
-- =============================================================================

CREATE TABLE robot_task_reports (
    id                          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    external_task_id            VARCHAR(255)    NOT NULL UNIQUE,
    robot_unit_id               UUID            NOT NULL REFERENCES robot_units(id),
    customer_profile_id         UUID            NOT NULL REFERENCES customer_profiles(id),
    brand                       VARCHAR(50)     NOT NULL,

    -- Common fields (all brands)
    cleaning_plan               VARCHAR(255),
    task_completion_pct         DECIMAL(5,2),
    start_time                  TIMESTAMPTZ,
    end_time                    TIMESTAMPTZ,
    work_efficiency_sqm_h       DECIMAL(10,2),
    working_time_seconds        INT,
    cleaning_area_sqm           DECIMAL(10,2),
    planned_area_sqm            DECIMAL(10,2),
    start_battery_pct           INT,
    end_battery_pct             INT,
    water_consumption_l         DECIMAL(8,2),

    -- Gausium-specific (nullable)
    brush_residual_pct          INT,
    filter_residual_pct         INT,
    suction_blade_residual_pct  INT,
    map_name                    TEXT,
    planned_polishing_area_sqm  DECIMAL(10,2),
    actual_polishing_area_sqm   DECIMAL(10,2),
    cleaning_mode                VARCHAR(100),
    task_report_png_uri         VARCHAR(500),
    task_end_status              INT,

    -- Sync metadata
    report_month                VARCHAR(7)      NOT NULL,
    synced_at                   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_robot_task_reports_customer_month ON robot_task_reports(report_month, customer_profile_id);
CREATE INDEX idx_robot_task_reports_robot_unit_month ON robot_task_reports(robot_unit_id, report_month);

-- ─── Gausium OAuth Tokens ────────────────────────────────────────────────────
CREATE TABLE gausium_oauth_tokens (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brand         VARCHAR(50)  NOT NULL UNIQUE,
    access_token  TEXT         NOT NULL,
    refresh_token TEXT         NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Customer Profile: LINE Notify ──────────────────────────────────────────
ALTER TABLE customer_profiles ADD COLUMN line_notify_token VARCHAR(255);
