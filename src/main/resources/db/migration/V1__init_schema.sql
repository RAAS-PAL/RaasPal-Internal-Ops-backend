-- =============================================================================
-- V1 - Initial Schema
-- RaasPal Robot Recommendation System
-- =============================================================================

-- ─── Users ───────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    full_name   VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Customer Profiles ───────────────────────────────────────────────────────
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
CREATE TABLE robots (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    model          VARCHAR(255) NOT NULL,
    manufacturer   VARCHAR(255) NOT NULL,
    specs_summary  TEXT,
    image_url      VARCHAR(500),
    datasheet_url  VARCHAR(500),
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── Robot Specs ─────────────────────────────────────────────────────────────
-- Core specs used by the hard-filter and recommendation engine
-- All spec fields are nullable — data is collected incrementally
-- pricing_type: SALE | RENTAL | BOTH
-- rental_price_thb is per month
CREATE TABLE robot_specs (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    robot_id                  UUID          NOT NULL UNIQUE REFERENCES robots(id),

    -- Physical
    weight_kg                 DECIMAL(8,2),
    cleaning_width_mm         INTEGER,

    -- Performance
    cleaning_efficiency_sqm_h INTEGER,
    speed_ms                  DECIMAL(5,2),
    noise_db                  DECIMAL(5,1),

    -- Battery
    battery_work_time_h       DECIMAL(5,2),
    charging_time_h           DECIMAL(5,2),

    -- Navigation & Environment
    navigation_type           VARCHAR(20),
    environment               VARCHAR(20),
    ip_rating                 VARCHAR(20),
    min_passable_width_mm     INTEGER,

    -- Pricing
    pricing_type              VARCHAR(10)   NOT NULL DEFAULT 'BOTH',
    sale_price_thb            DECIMAL(15,2),
    rental_price_thb          DECIMAL(15,2),

    -- Catch-all for remaining specs not yet promoted to columns
    additional_specs          TEXT,

    created_at                TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- ─── Requirements ────────────────────────────────────────────────────────────
-- Ownership anchored to customer_profile_id
-- pricing_preference: SALE | RENTAL | BOTH
CREATE TABLE requirements (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_profile_id  UUID          NOT NULL REFERENCES customer_profiles(id),
    title                VARCHAR(255)  NOT NULL,
    description          TEXT,
    min_payload_kg       DECIMAL(10,2),
    max_payload_kg       DECIMAL(10,2),
    min_reach_mm         INTEGER,
    max_reach_mm         INTEGER,
    environment          VARCHAR(20),
    pricing_preference   VARCHAR(10),
    budget_thb           DECIMAL(15,2),
    priority_notes       TEXT,
    status               VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    created_by           UUID          REFERENCES users(id),
    created_at           TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP     NOT NULL DEFAULT NOW()
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
-- One row per robot ranked within a recommendation session
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
-- Per-criterion score breakdown for each recommendation item
CREATE TABLE scores (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recommendation_item_id  UUID         NOT NULL REFERENCES recommendation_items(id),
    criterion               VARCHAR(100) NOT NULL,
    score                   DECIMAL(8,4) NOT NULL,
    weight                  DECIMAL(5,4),
    weighted_score          DECIMAL(8,4),
    notes                   TEXT,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─── File Uploads ─────────────────────────────────────────────────────────────
CREATE TABLE file_uploads (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    original_filename  VARCHAR(500) NOT NULL,
    stored_filename    VARCHAR(500) NOT NULL,
    content_type       VARCHAR(100),
    file_size          BIGINT,
    entity_type        VARCHAR(50),
    entity_id          UUID,
    uploaded_by        UUID         REFERENCES users(id),
    is_locked          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW()
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
-- Immutable event log — rows are never updated or deleted
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

CREATE INDEX idx_users_role                          ON users(role);
CREATE INDEX idx_customer_profiles_user_id           ON customer_profiles(user_id);
CREATE INDEX idx_customer_profiles_created_by        ON customer_profiles(created_by);
CREATE INDEX idx_robot_specs_environment             ON robot_specs(environment);
CREATE INDEX idx_robot_specs_pricing_type            ON robot_specs(pricing_type);
CREATE INDEX idx_requirements_customer_profile_id    ON requirements(customer_profile_id);
CREATE INDEX idx_requirements_status                 ON requirements(status);
CREATE INDEX idx_requirements_environment            ON requirements(environment);
CREATE INDEX idx_requirements_pricing_preference     ON requirements(pricing_preference);
CREATE INDEX idx_recommendations_requirement_id      ON recommendations(requirement_id);
CREATE INDEX idx_recommendations_status              ON recommendations(status);
CREATE INDEX idx_recommendation_items_recommendation ON recommendation_items(recommendation_id);
CREATE INDEX idx_recommendation_items_robot          ON recommendation_items(robot_id);
CREATE INDEX idx_scores_recommendation_item          ON scores(recommendation_item_id);
CREATE INDEX idx_file_uploads_entity                 ON file_uploads(entity_type, entity_id);
CREATE INDEX idx_reports_recommendation_id           ON reports(recommendation_id);
CREATE INDEX idx_audit_logs_entity                   ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor                    ON audit_logs(actor_id);