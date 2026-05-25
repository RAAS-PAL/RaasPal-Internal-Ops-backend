-- =============================================================================
-- V1 - Initial Schema
-- RaasPal Robot Recommendation System
-- All spec columns are nullable: blank = "unknown", never = "zero" or "bad".
-- =============================================================================

-- ─── Users ───────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    full_name   VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL,          -- ADMIN | RAASPAL_TEAM | CUSTOMER
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Customer Profiles ───────────────────────────────────────────────────────
-- Created atomically with User when RAASPAL_TEAM onboards a customer.
CREATE TABLE customer_profiles (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL UNIQUE REFERENCES users(id),
    company_name  VARCHAR(255) NOT NULL,
    industry      VARCHAR(255),
    contact_phone VARCHAR(50),
    address       TEXT,
    notes         TEXT,
    created_by    UUID         REFERENCES users(id),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Robots ──────────────────────────────────────────────────────────────────
-- Identity + universal fields only. Type-specific specs live in robot_specs.
-- price_band is a coarse guide for budget scoring (LOW | MODERATE | HIGH).
-- test_status distinguishes "not yet tested" from "verified".
CREATE TABLE robots (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    brand         VARCHAR(255) NOT NULL,
    model         VARCHAR(255) NOT NULL,
    robot_type    VARCHAR(20)  NOT NULL,                   -- CLEANING | DELIVERY | SECURITY
    test_status   VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',   -- DRAFT | UNDER_TESTING | VERIFIED
    price_band    VARCHAR(10),                             -- LOW | MODERATE | HIGH
    image_url     VARCHAR(500),
    datasheet_url VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Robot Specs (cleaning-type) ─────────────────────────────────────────────
-- ~50 columns parsed from the raw datasheet CSV (Phase 2 import).
-- Grouped into the 6 scoring dimensions used by the recommendation engine.
-- ALL spec columns are nullable — blank cells from the datasheet become NULL.
CREATE TABLE robot_specs (
    id         UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    robot_id   UUID  NOT NULL UNIQUE REFERENCES robots(id),

    -- ── Physical ──────────────────────────────────────────────────────────────
    weight_kg              DECIMAL(8,2),
    length_mm              INTEGER,
    width_mm               INTEGER,
    height_mm              INTEGER,
    cleaning_width_mm      INTEGER,
    brush_pressure_kg      DECIMAL(6,2),
    vacuum_pressure_kpa    DECIMAL(6,2),

    -- ── Dimension 1 – Capability: cleaning functions ──────────────────────────
    func_sweep             BOOLEAN,
    func_sweep_vacuum      BOOLEAN,
    func_dry_mop           BOOLEAN,
    func_wet_mop           BOOLEAN,
    func_roller_scrub      BOOLEAN,
    func_disc_scrub        BOOLEAN,

    -- ── Dimension 1 – Capability: efficiency (m²/h per mode) ─────────────────
    efficiency_sweep_sqm_h         INTEGER,
    efficiency_scrub_sqm_h         INTEGER,
    efficiency_mop_sqm_h           INTEGER,
    efficiency_sweep_scrub_sqm_h   INTEGER,
    efficiency_vacuum_sqm_h        INTEGER,

    -- ── Dimension 3 – Coverage capacity ──────────────────────────────────────
    tank_clean_l           DECIMAL(6,2),
    tank_waste_l           DECIMAL(6,2),
    tank_trash_l           DECIMAL(6,2),
    dust_bag_l             DECIMAL(6,2),
    speed_ms               DECIMAL(5,2),

    -- ── Battery ───────────────────────────────────────────────────────────────
    battery_type              VARCHAR(20),
    battery_voltage_v         DECIMAL(6,1),
    battery_capacity_ah       DECIMAL(8,2),
    charging_time_hr          DECIMAL(5,2),
    battery_work_hr           DECIMAL(5,2),
    work_time_sweep_hr        DECIMAL(5,2),
    work_time_scrub_hr        DECIMAL(5,2),
    work_time_sweep_vacuum_hr DECIMAL(5,2),

    -- ── Dimension 2 – Size & access ───────────────────────────────────────────
    min_passable_width_mm  INTEGER,
    min_passable_height_mm INTEGER,
    max_narrow_cross_mm    INTEGER,
    min_turn_width_mm      INTEGER,
    min_edge_from_wall_mm  INTEGER,
    max_step_height_mm     INTEGER,
    slope_angle_deg        DECIMAL(5,1),

    -- ── Dimension 4 – Floor suitability ───────────────────────────────────────
    floor_paving_blocks    BOOLEAN,
    floor_granite          BOOLEAN,
    floor_marble           BOOLEAN,
    floor_terrazzo         BOOLEAN,
    floor_terracotta       BOOLEAN,
    floor_ceramic          BOOLEAN,
    floor_smooth_concrete  BOOLEAN,
    floor_coarse_concrete  BOOLEAN,
    floor_stamped_concrete BOOLEAN,
    floor_asphalt          BOOLEAN,
    floor_epoxy            BOOLEAN,
    floor_tile             BOOLEAN,
    floor_short_carpet     BOOLEAN,
    floor_long_carpet      BOOLEAN,
    floor_spc              BOOLEAN,
    floor_laminate         BOOLEAN,
    floor_vinyl            BOOLEAN,

    -- Floor tile layout sizes
    layout_2x2             BOOLEAN,
    layout_4x4             BOOLEAN,
    layout_8x8             BOOLEAN,
    layout_10x10           BOOLEAN,
    layout_12x12           BOOLEAN,
    layout_20x20           BOOLEAN,

    -- ── Dimension 5 – Environment fit ─────────────────────────────────────────
    is_indoor              BOOLEAN,
    is_outdoor             BOOLEAN,
    ip_rating              VARCHAR(20),
    hepa                   BOOLEAN,

    -- ── Dimension 6 – Operational quality ─────────────────────────────────────
    noise_db               DECIMAL(5,1),
    nav_lidar_2d           BOOLEAN,
    nav_lidar_3d           BOOLEAN,
    nav_vslam              BOOLEAN,
    has_workstation        BOOLEAN,
    dock_charge            BOOLEAN,
    manual_charge          BOOLEAN,
    has_spot_ai            BOOLEAN,

    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ─── File Uploads ─────────────────────────────────────────────────────────────
-- Defined before requirements so source_file_id FK can reference it.
-- entity_type + entity_id link any file to any record (requirement, report, etc.)
CREATE TABLE file_uploads (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    original_filename  VARCHAR(500) NOT NULL,
    stored_filename    VARCHAR(500) NOT NULL,
    content_type       VARCHAR(100),
    file_size          BIGINT,
    entity_type        VARCHAR(50),   -- e.g. "requirement", "report"
    entity_id          UUID,
    uploaded_by        UUID         REFERENCES users(id),
    is_locked          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Requirements ────────────────────────────────────────────────────────────
-- Central intake entity. Ownership anchored to customer_profile_id.
-- cleaning_functions and floor_types are TEXT[] — multi-select from the wizard.
--
-- input_source tracks how the requirement was created:
--   WEB_FORM       → customer/team filled the structured web form
--   CSV_IMPORT     → parsed from an uploaded CSV file
--   FILE_EXTRACTED → AI pre-filled fields from an uploaded PDF or image
-- source_file_id links back to the file that generated this requirement (nullable).
CREATE TABLE requirements (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_profile_id   UUID         NOT NULL REFERENCES customer_profiles(id),
    robot_type            VARCHAR(20)  NOT NULL DEFAULT 'CLEANING',
    title                 VARCHAR(255) NOT NULL,
    description           TEXT,                  -- free-text; feeds AI semantic matching
    environment           VARCHAR(20),           -- INDOOR | OUTDOOR | CLEANROOM | HAZARDOUS
    cleaning_functions    TEXT[],                -- e.g. {sweep,scrub,mop,vacuum}
    floor_types           TEXT[],                -- e.g. {marble,ceramic,vinyl}
    min_passable_width_mm INTEGER,
    coverage_area_sqm     INTEGER,
    budget_band           VARCHAR(10),           -- LOW | MODERATE | HIGH
    priority_notes        TEXT,
    input_source          VARCHAR(20)  NOT NULL DEFAULT 'WEB_FORM',
    source_file_id        UUID         REFERENCES file_uploads(id),
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_by            UUID         REFERENCES users(id),
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Recommendations ─────────────────────────────────────────────────────────
CREATE TABLE recommendations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    requirement_id  UUID        NOT NULL REFERENCES requirements(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ai_explanation  TEXT,
    created_by      UUID        REFERENCES users(id),
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ─── Recommendation Items ────────────────────────────────────────────────────
-- One row per robot ranked within a recommendation session.
CREATE TABLE recommendation_items (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recommendation_id  UUID         NOT NULL REFERENCES recommendations(id),
    robot_id           UUID         NOT NULL REFERENCES robots(id),
    rank_position      INTEGER,
    total_score        DECIMAL(8,4),
    ai_reasoning       TEXT,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Scores ──────────────────────────────────────────────────────────────────
-- Per-dimension score breakdown for each recommendation item (audit trail).
CREATE TABLE scores (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recommendation_item_id  UUID         NOT NULL REFERENCES recommendation_items(id),
    dimension               VARCHAR(100) NOT NULL,   -- capability_match, size_access_fit, etc.
    score                   DECIMAL(8,4) NOT NULL,   -- normalised 0–1
    weight                  DECIMAL(5,4),
    weighted_score          DECIMAL(8,4),
    notes                   TEXT,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Reports ─────────────────────────────────────────────────────────────────
CREATE TABLE reports (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    recommendation_id  UUID          NOT NULL REFERENCES recommendations(id),
    file_name          VARCHAR(500),
    file_path          VARCHAR(1000),
    generated_by       UUID          REFERENCES users(id),
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- ─── Audit Logs ──────────────────────────────────────────────────────────────
-- Immutable event log — rows are never updated or deleted.
CREATE TABLE audit_logs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id     UUID,
    actor_email  VARCHAR(255),
    action       VARCHAR(50)  NOT NULL,
    entity_type  VARCHAR(100) NOT NULL,
    entity_id    UUID,
    old_value    TEXT,
    new_value    TEXT,
    details      TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- Indexes
-- =============================================================================
CREATE INDEX idx_users_role                           ON users(role);
CREATE INDEX idx_customer_profiles_user_id            ON customer_profiles(user_id);
CREATE INDEX idx_customer_profiles_created_by         ON customer_profiles(created_by);
CREATE INDEX idx_robots_type                          ON robots(robot_type);
CREATE INDEX idx_robots_test_status                   ON robots(test_status);
CREATE INDEX idx_robot_specs_robot_id                 ON robot_specs(robot_id);
CREATE INDEX idx_robot_specs_environment              ON robot_specs(is_indoor, is_outdoor);
CREATE INDEX idx_file_uploads_entity                  ON file_uploads(entity_type, entity_id);
CREATE INDEX idx_requirements_customer_profile_id     ON requirements(customer_profile_id);
CREATE INDEX idx_requirements_status                  ON requirements(status);
CREATE INDEX idx_requirements_robot_type              ON requirements(robot_type);
CREATE INDEX idx_requirements_input_source            ON requirements(input_source);
CREATE INDEX idx_recommendations_requirement_id       ON recommendations(requirement_id);
CREATE INDEX idx_recommendations_status               ON recommendations(status);
CREATE INDEX idx_recommendation_items_recommendation  ON recommendation_items(recommendation_id);
CREATE INDEX idx_recommendation_items_robot           ON recommendation_items(robot_id);
CREATE INDEX idx_scores_recommendation_item           ON scores(recommendation_item_id);
CREATE INDEX idx_reports_recommendation_id            ON reports(recommendation_id);
CREATE INDEX idx_audit_logs_entity                    ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor                     ON audit_logs(actor_id);