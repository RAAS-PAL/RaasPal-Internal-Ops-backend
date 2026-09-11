-- PM 52-week planning: a local mirror of the two monday PM boards.
--
-- Why a mirror at all. The forward PM schedule already exists in monday, as
-- roughly 4,400 dated subitems under 511 parent items across PM Cleaning
-- (2048972900) and PM Delivery (4129404143). What does not exist is any way to
-- see them on one timeline: a 52-week grid needs a full year of rows sorted and
-- grouped by geography, which over the monday API means paging two boards on
-- every page load. Mirroring turns that into one indexed query.
--
-- Why NOT case_ticket, which already mirrors the CM and Installation boards.
-- Two reasons. PM data is two-level - a contract and its visits - and flattening
-- it would lose the parent. More seriously, KpiCaseMetricsService aggregates over
-- case_ticket; adding several thousand PM rows to it risks silently changing the
-- CM and Installation numbers on a dashboard nobody would think to re-check.
--
-- Version numbering. V38 follows directly from V37, the highest migration on main,
-- because this branch is the next one to merge. That is a deliberate claim on the
-- number: feat/re-kpi-dashboard also carries a V38 (add_case_ticket_sync) through
-- V41, so once this lands, that branch must renumber its four files to V39-V42
-- before it can merge. Flyway refuses to start when two files share a version
-- ("Found more than one migration with version 38"), so the clash surfaces the
-- moment that branch rebases onto main rather than silently at deploy time.
--
-- This migration only adds tables. It alters nothing existing and drops nothing,
-- which matters because local development and production share one database:
-- starting the backend locally on this branch applies this file to production.

CREATE TABLE pm_contract (
    id                UUID PRIMARY KEY,
    source_board_id   VARCHAR(32)  NOT NULL,
    source_item_id    VARCHAR(32)  NOT NULL,
    service_line      VARCHAR(16)  NOT NULL,
    item_name         TEXT,
    group_title       TEXT,
    project_raw       TEXT,
    customer_name_raw TEXT,
    province_raw      TEXT,
    region_raw        TEXT,
    district_raw      TEXT,
    province_resolved VARCHAR(64),
    region_resolved   VARCHAR(32),
    zone_resolved     VARCHAR(32),
    lat               NUMERIC(10, 7),
    lng               NUMERIC(10, 7),
    contract_type     TEXT,
    warranty_text     TEXT,
    warranty_start    DATE,
    warranty_end      DATE,
    company           VARCHAR(128),
    robot_model       TEXT,
    robot_serials     TEXT,
    robot_count       INTEGER,
    raw_columns       TEXT,
    source_updated_at TIMESTAMPTZ,
    first_seen_at     TIMESTAMPTZ  NOT NULL,
    last_synced_at    TIMESTAMPTZ  NOT NULL,
    is_present        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_pm_contract_source_item UNIQUE (source_board_id, source_item_id)
);

COMMENT ON COLUMN pm_contract.region_raw IS
    'monday''s own region cell, kept only for diagnosis. Never used for grouping: it holds 12 spellings for about 7 regions. region_resolved is derived from the province instead.';
COMMENT ON COLUMN pm_contract.province_resolved IS
    'Canonical province, or UNASSIGNED where monday''s cell was empty or unrecognised. About half the Delivery contracts land on UNASSIGNED.';
COMMENT ON COLUMN pm_contract.company IS
    'Chain/brand the site belongs to, derived from item_name at sync time - the part before ":", else the first word with a generic Thai business prefix (บริษัท, หจก.) skipped. Derived because neither board has a company column: project_raw is empty on 44% of rows and customer_name_raw on 64%.';
COMMENT ON COLUMN pm_contract.is_present IS
    'FALSE once the source item stops coming back from monday. Rows are marked, never deleted, so visit history survives a tidied board.';

CREATE TABLE pm_visit (
    id                UUID PRIMARY KEY,
    pm_contract_id    UUID         NOT NULL REFERENCES pm_contract (id) ON DELETE CASCADE,
    source_board_id   VARCHAR(32)  NOT NULL,
    source_item_id    VARCHAR(32)  NOT NULL,
    visit_name        TEXT,
    pm_sequence       INTEGER,
    plan_date         DATE,
    action_date       DATE,
    time_text         TEXT,
    status_raw        TEXT,
    status_bucket     VARCHAR(16)  NOT NULL,
    owner_names       TEXT,
    raw_columns       TEXT,
    source_updated_at TIMESTAMPTZ,
    first_seen_at     TIMESTAMPTZ  NOT NULL,
    last_synced_at    TIMESTAMPTZ  NOT NULL,
    is_present        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_pm_visit_source_item UNIQUE (source_board_id, source_item_id)
);

COMMENT ON COLUMN pm_visit.plan_date IS
    'Planned visit date (monday "Plan"). The spine of the 52-week grid. Null on a large minority of rows - those are unscheduled PM and the planner counts them explicitly rather than hiding them.';
COMMENT ON COLUMN pm_visit.action_date IS
    'Actual visit date (monday "Action"). Filled on well under a third of rows, so completion is read from status_bucket, not from this.';
COMMENT ON COLUMN pm_visit.status_bucket IS
    'COMPLETED | IN_PROGRESS | PLANNED | UNPLANNED, derived at sync time. Overdue is NOT stored: it depends on today and is computed at query time.';

-- The grid asks for a year of visits at a time, always ordered by date and
-- usually narrowed by status, so both go in one index.
CREATE INDEX idx_pm_visit_plan_date ON pm_visit (plan_date);
CREATE INDEX idx_pm_visit_status_plan_date ON pm_visit (status_bucket, plan_date);
CREATE INDEX idx_pm_visit_contract ON pm_visit (pm_contract_id);
CREATE INDEX idx_pm_contract_province ON pm_contract (province_resolved);
CREATE INDEX idx_pm_contract_region_zone ON pm_contract (region_resolved, zone_resolved);
-- The company filter excludes whole chains at once, so it reads this on every query.
CREATE INDEX idx_pm_contract_company ON pm_contract (company);

CREATE TABLE pm_sync_run (
    id                UUID PRIMARY KEY,
    source_board_id   VARCHAR(32),
    service_line      VARCHAR(16),
    status            VARCHAR(16)  NOT NULL,
    triggered_by      VARCHAR(255),
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ,
    contracts_read    INTEGER,
    visits_read       INTEGER,
    contracts_written INTEGER,
    visits_written    INTEGER,
    marked_absent     INTEGER,
    error_message     TEXT
);

CREATE INDEX idx_pm_sync_run_started_at ON pm_sync_run (started_at DESC);
