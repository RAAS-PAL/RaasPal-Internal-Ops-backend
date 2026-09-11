-- =============================================================================
-- V38 - Case tickets mirrored from monday.com, for the RE KPI dashboard
--
-- The Robot Engineering KPI deck (Total CM Cases, SLA, First Time Fix) is built
-- today by hand from the monday.com Cleaning Tickets and Delivery Tickets boards
-- plus spreadsheets. This is the first table the dashboard can compute from: one
-- row per board ticket, refreshed by a scheduled sync and readable at
-- /api/v1/kpi/cm-cases.
--
-- case_ticket is table 1 of the parked V34 design
-- (V34__add_case_report_tables.sql.txt), kept column-for-column so the Daily
-- Pending Case Report can build on the same mirror later instead of syncing the
-- boards twice. Six columns are added for the KPI maths: service_line, ticket_no,
-- issue_level, close_date, is_closed and serials_normalised. The other eight V34
-- tables (comments, status history, overrides, report definitions, ...) stay
-- parked and arrive with that feature as V39+.
--
-- Purely additive: nothing existing references these tables and nothing here
-- references anything existing, so the deployed backend is unaffected. That
-- matters because local development and production share one database, and
-- starting the backend locally on this branch applies this migration to it.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. case_ticket - current state of one ticket
--
-- One row per ticket, updated in place on each sync: this table answers "what is
-- true right now". Column ids differ per board (`text` is Main Issue on Cleaning
-- but Solution on Delivery), so which board column feeds which field is
-- configuration (app.kpi.monday.boards[n].columns), not schema.
-- -----------------------------------------------------------------------------
CREATE TABLE case_ticket (
    id                  UUID         PRIMARY KEY,

    source              VARCHAR(20)  NOT NULL,   -- MONDAY (GOOGLE_SHEET / EXCEL later)
    source_board_id     TEXT         NOT NULL,   -- '3451717331' cleaning, '1647612496' delivery
    source_item_id      TEXT         NOT NULL,
    source_group_id     TEXT,
    source_group_title  TEXT,                    -- 'All Case', 'Done Check', ...

    -- Which of the two RE service lines the ticket belongs to. Derived from the
    -- board it came from, stored so the KPI split never needs the board config.
    service_line        VARCHAR(16)  NOT NULL,   -- CLEANING | DELIVERY

    item_name           TEXT,
    ticket_no           TEXT,

    -- Raw, exactly as the board holds it. One ticket can name several robots,
    -- separated by '/' on the cleaning board and by 'และ' on delivery. Splitting
    -- on the way in would lose the ability to trace a row back to its ticket.
    serial_numbers      TEXT,
    -- The same serials upper-cased, whitespace-stripped and '|'-joined. Repeat
    -- detection joins on this; the board's own casing is not consistent
    -- (L352507605060ZK vs L352507606060zK are the same robot).
    serials_normalised  TEXT,

    -- Also raw. The boards carry live typos ('Marko ระนอง' for Makro) and about
    -- half the project tags are blank. Normalisation is a later feature
    -- (V34's case_branch_alias); storing the original means it can fix history.
    project_raw         TEXT,
    branch_raw          TEXT,
    branch_code_raw     TEXT,                    -- delivery board's 'Branch code' tag: M524, Y034
    province_raw        TEXT,                    -- drives the SLA threshold: 3 days metro, 5 upcountry

    robot_model         TEXT,
    status              TEXT,
    sup_status          TEXT,
    issue_level         TEXT,
    main_issue          TEXT,

    open_date           DATE,
    close_date          DATE,
    -- True when close_date is set or the status is one the board config lists as
    -- finished. Kept as a column so the aggregation does not re-read config.
    is_closed           BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Every column value the board returned, as a JSON array of
    -- {id, title, type, text}. Costs almost nothing and means the first time a
    -- column is mapped that was not mapped before, the data is already here
    -- instead of needing a re-sync of the board. Plain TEXT rather than JSONB:
    -- nothing queries into it yet, and TEXT is what the H2 test profile and the
    -- JPA mapping agree on without a custom type.
    raw_columns         TEXT,

    -- monday's updated_at, in UTC. A sync counts a row as "updated" only when
    -- this moved, so the run summary says how many tickets really changed.
    source_updated_at   TIMESTAMP,
    first_seen_at       TIMESTAMP    NOT NULL,
    last_synced_at      TIMESTAMP    NOT NULL,

    -- Set false when a ticket is no longer returned by any of the board's synced
    -- groups (deleted or archived on monday). Never deleted: the KPI for a past
    -- month should not change because someone tidied the board.
    is_present          BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_case_ticket_source_item UNIQUE (source, source_item_id)
);

CREATE INDEX idx_case_ticket_board_present ON case_ticket (source_board_id, is_present);
CREATE INDEX idx_case_ticket_open_date     ON case_ticket (open_date);
CREATE INDEX idx_case_ticket_line_open     ON case_ticket (service_line, open_date);


-- -----------------------------------------------------------------------------
-- 2. case_ticket_sync_run - one sync of one board, and what it did
--
-- The audit trail the console shows next to the "last synced" stamp. A run that
-- fails leaves a FAILED row with the monday error, so a dashboard showing stale
-- numbers can be traced to a bad token or a hidden board rather than guessed at.
-- -----------------------------------------------------------------------------
CREATE TABLE case_ticket_sync_run (
    id                   UUID         PRIMARY KEY,
    source_board_id      TEXT         NOT NULL,
    service_line         VARCHAR(16)  NOT NULL,

    status               VARCHAR(16)  NOT NULL,  -- RUNNING | SUCCEEDED | FAILED
    triggered_by         VARCHAR(16)  NOT NULL,  -- SCHEDULED | MANUAL

    started_at           TIMESTAMP    NOT NULL,
    finished_at          TIMESTAMP,

    groups_read          INTEGER      NOT NULL DEFAULT 0,
    items_read           INTEGER      NOT NULL DEFAULT 0,
    items_inserted       INTEGER      NOT NULL DEFAULT 0,
    items_updated        INTEGER      NOT NULL DEFAULT 0,
    items_unchanged      INTEGER      NOT NULL DEFAULT 0,
    items_marked_absent  INTEGER      NOT NULL DEFAULT 0,

    error_message        TEXT
);

CREATE INDEX idx_case_ticket_sync_run_started ON case_ticket_sync_run (started_at DESC);
