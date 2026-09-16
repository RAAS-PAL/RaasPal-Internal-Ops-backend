-- =============================================================================
-- V38 - Daily Pending Case Report
--
-- Nine tables behind one feature: pull service tickets and their comment threads
-- from monday.com, let AI fill the columns nobody stores anywhere, have a human
-- check it, then deliver the result to LINE.
--
-- Source-neutral on purpose. Every column here says "source", never "monday" —
-- the AOT data currently lives in BOTH a monday board and a Google Sheet the RE
-- team keeps, and which one wins is not settled. Naming the tables after today's
-- adapter would bake that unsettled choice into the schema, and renaming a table
-- with data in it is a migration plus code churn across every generator.
--
-- Purely additive: no foreign keys point OUT of this feature except into users,
-- so nothing existing can break, and the whole feature can be dropped by
-- deleting these nine tables.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. case_ticket - current state of one ticket
--
-- One row per ticket, updated in place on each sync. History lives in
-- case_ticket_status_history; this table answers "what is true right now".
-- -----------------------------------------------------------------------------
CREATE TABLE case_ticket (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    source              VARCHAR(20)  NOT NULL,   -- MONDAY | GOOGLE_SHEET
    source_board_id     TEXT         NOT NULL,   -- '3451717331' cleaning, '1647612496' delivery
    source_item_id      TEXT         NOT NULL,
    source_group_id     TEXT,
    source_group_title  TEXT,                    -- 'All Case'

    item_name           TEXT,

    -- Raw, exactly as the board holds it. One ticket can name several robots,
    -- separated by '/' on the cleaning board and by 'และ' on delivery, and the
    -- report splits those into one row each. Splitting on the way IN would lose
    -- the ability to trace a row back to its ticket.
    serial_numbers      TEXT,

    -- Also raw. The board carries live typos - 'ท่าอาศยานสุวรรณภูมิ' is missing a ก,
    -- 'Marko ระนอง' should be Makro - and roughly half the project tags are blank.
    -- Normalisation happens at report time through case_branch_alias, so a
    -- corrected alias fixes history too rather than only new rows.
    project_raw         TEXT,
    branch_raw          TEXT,
    branch_code_raw     TEXT,                    -- delivery board's 'Branch code' tag: M524, Y034

    -- Set on the ticket itself, when the boards carry a จังหวัด dropdown. This is
    -- the best source of province: stated at ticket creation by someone who knows
    -- where the robot is, rather than inferred from a branch name or joined through
    -- a robot list that can be stale. A blank here shows as "-" in the report the
    -- next morning, which is a fast enough feedback loop to keep it filled.
    province_raw        TEXT,

    robot_model         TEXT,
    status              TEXT,
    sup_status          TEXT,
    main_issue          TEXT,
    solution            TEXT,

    open_date           DATE,
    re_action_date      DATE,

    -- The whole column_values payload, unparsed. Costs almost nothing and means
    -- the first time someone needs a column we did not map, it is already here
    -- instead of needing a re-sync of the board.
    raw_columns         JSONB,

    source_updated_at   TIMESTAMP,
    first_seen_at       TIMESTAMP    NOT NULL DEFAULT now(),
    last_synced_at      TIMESTAMP    NOT NULL DEFAULT now(),

    -- Set false when a ticket leaves the watched group, i.e. the case closed.
    -- Never deleted: these tickets appear in reports already sent to customers,
    -- and deleting them would erase the record of what was reported.
    is_present          BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_case_ticket_source_item UNIQUE (source, source_item_id)
);

CREATE INDEX idx_case_ticket_board_present ON case_ticket (source_board_id, is_present);
CREATE INDEX idx_case_ticket_open_date     ON case_ticket (open_date);


-- -----------------------------------------------------------------------------
-- 2. case_ticket_update - the comment threads
--
-- The only place several report columns exist at all. Part Received, Required
-- Part and Waiting have no board column holding them: date_mm3b365t,
-- dropdown_mknqq9fm and text_mksf2s9c were verified empty on all 46 live
-- tickets. Whatever the AI produces for those, it produces from here.
-- -----------------------------------------------------------------------------
CREATE TABLE case_ticket_update (
    id                UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    case_ticket_id    UUID  NOT NULL REFERENCES case_ticket (id) ON DELETE CASCADE,

    source_update_id  TEXT  NOT NULL,
    parent_update_id  TEXT,                      -- NULL = top-level, else a reply

    body              TEXT,                      -- text_body, never the HTML body

    -- Worth keeping: the '*status*' convention that marks a case's state is one
    -- person's habit, not a team standard - every observed instance was written
    -- by "Boss". A prompt can weight comments by author because of this column.
    creator_name      TEXT,

    posted_at         TIMESTAMP,
    synced_at         TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT uq_case_ticket_update UNIQUE (case_ticket_id, source_update_id)
);

CREATE INDEX idx_case_ticket_update_ticket ON case_ticket_update (case_ticket_id, posted_at DESC);


-- -----------------------------------------------------------------------------
-- 3. case_ticket_status_history - what the Delivery report calls "Solution"
--
-- The MK report's Solution column is not prose. It is a status log, one line per
-- change: "24-Aug อยู่ระหว่างจัดส่งอะไหล่" is simply the date plus the value of the
-- board's status column on that date.
--
-- So the daily sync builds that column for free. A row is written ONLY when the
-- status differs from the previous one, which keeps the table small and makes
-- reading it back a straight ordered select.
--
-- The UNIQUE below caps it at one row per ticket per day: a status flipped twice
-- in an afternoon should not put two lines under the same date in a report.
-- -----------------------------------------------------------------------------
CREATE TABLE case_ticket_status_history (
    id              UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    case_ticket_id  UUID      NOT NULL REFERENCES case_ticket (id) ON DELETE CASCADE,

    status          TEXT,
    sup_status      TEXT,

    observed_on     DATE      NOT NULL,
    observed_at     TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT uq_case_ticket_status_day UNIQUE (case_ticket_id, observed_on)
);

CREATE INDEX idx_case_ticket_status_history ON case_ticket_status_history (case_ticket_id, observed_on);


-- -----------------------------------------------------------------------------
-- 4. case_ticket_override - a correction that survives tomorrow
--
-- This report runs daily and its cases live for weeks - the live AOT data has
-- cases open 30, 41 and 52 days. Without this table, a staff member who fixes an
-- AI mistake would re-fix the same mistake every morning until the case closed,
-- and would rightly stop trusting the tool.
--
-- ⚠️ An override is NOT permanent. source_seen_at records what the ticket looked
-- like when the human made the call; once the ticket gets new activity the AI
-- knows something the human did not, so the override is dropped and the row goes
-- back to being generated. A frozen "waiting for parts" long after the parts
-- arrived is worse than the original error, because nobody would notice it.
--
-- field_name is deliberately unconstrained text: which fields are editable is a
-- decision for the generator and the console, not the schema, so changing it must
-- never need a migration.
--
-- Computed fields are overridable too, not only AI-generated ones. SLA especially:
-- the rule maps the board's status onto a colour, but whether a delay is really
-- the customer's fault is something only a person knows, and that colour is a
-- public statement about who is late.
--
-- ⚠️ Renaming a generated field is therefore a data migration, not a schema one:
--    UPDATE case_ticket_override SET field_name = 'new' WHERE field_name = 'old';
--    Skip it and every saved correction silently stops applying, with no error.
-- -----------------------------------------------------------------------------
CREATE TABLE case_ticket_override (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    case_ticket_id  UUID        NOT NULL REFERENCES case_ticket (id) ON DELETE CASCADE,

    -- 'waiting' | 'waiting_from' | 'part_received' | 'sla' | ...
    field_name      VARCHAR(64) NOT NULL,
    value           TEXT,

    -- Why the human disagreed with the generated value. Optional for most fields;
    -- an SLA override should carry one, because turning a red row yellow tells the
    -- customer the delay was not RAASPAL's, and that claim should have a reason
    -- attached to it six months later.
    reason          TEXT,

    set_by          UUID        REFERENCES users (id),
    set_at          TIMESTAMP   NOT NULL DEFAULT now(),
    source_seen_at  TIMESTAMP,                   -- ticket's last_synced_at when set

    CONSTRAINT uq_case_ticket_override UNIQUE (case_ticket_id, field_name)
);


-- -----------------------------------------------------------------------------
-- 5. case_report_definition - one row per report
--
-- "All Pending Cases", "AOT", "MK". Adding a fourth is an INSERT, not a deploy.
--
-- generator_key names the Java class that builds the rows, the same wiring
-- TelemetryAdapterRegistry uses to map "gausium" to GausiumAdapter. Column
-- layout and filter logic live in that class, not here, so a change to AOT
-- cannot affect MK.
-- -----------------------------------------------------------------------------
CREATE TABLE case_report_definition (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    code             VARCHAR(50)  NOT NULL UNIQUE,   -- AOT_PENDING | MK_PENDING | ALL_PENDING
    name             TEXT         NOT NULL,
    description      TEXT,

    generator_key    VARCHAR(50)  NOT NULL,

    source           VARCHAR(20)  NOT NULL DEFAULT 'MONDAY',
    source_board_id  TEXT         NOT NULL,
    source_group_id  TEXT         NOT NULL,

    -- AUTO sends without asking; MANUAL parks the run at AWAITING_APPROVAL.
    -- ⚠️ AUTO is still gated by app.casereport.auto-send-enabled, so a machine
    -- running in parallel during the Render->Lightsail migration cannot send.
    delivery_mode    VARCHAR(10)  NOT NULL DEFAULT 'MANUAL'
                                  CHECK (delivery_mode IN ('AUTO', 'MANUAL')),

    schedule_cron    VARCHAR(50),
    schedule_zone    VARCHAR(50)  NOT NULL DEFAULT 'Asia/Bangkok',

    -- SLA in CALENDAR days. Confirmed with the RE team, 2026-08-27/28:
    --
    --   Cleaning reports  ->  3 days everywhere        (metro = upcountry = 3)
    --   Delivery report   ->  3 days in greater Bangkok, 5 days elsewhere
    --
    -- So three of the four reports never need a province at all, and only the
    -- Delivery board's tickets have to carry one.
    --
    -- Which province counts as "metro" is app config (app.casereport.metro-provinces),
    -- not a column here, because it is one list shared by every report and adding a
    -- province to it should not mean editing every definition row.
    --
    -- Verified against all 27 rows of the 24 and 25 Aug 2026 reports. The rows that
    -- look wrong under a single threshold are upcountry: Y116 (Hat Yai) and M102
    -- (Ubon) both sit at Days = 5 and are green, because their limit is 5, not 3.
    --
    -- ⚠️ Compared with '>', not '>=': at exactly the limit a case is still within
    -- SLA, so lateness starts the day after.
    --
    -- Editable by staff in the console, per report, because customers may sign
    -- different terms and none should need a deploy to correct.
    sla_days_metro      INTEGER   NOT NULL DEFAULT 3 CHECK (sla_days_metro > 0),
    sla_days_upcountry  INTEGER   NOT NULL DEFAULT 5 CHECK (sla_days_upcountry > 0),

    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);


-- -----------------------------------------------------------------------------
-- 6. case_report_recipient - where a report is delivered
--
-- ⚠️ target_id can only ever be an id LINE has already handed us, from a follow
-- or join webhook. There is no lookup by phone number, email or company name,
-- and only one LINE Official Account can sit in a group chat at a time.
-- -----------------------------------------------------------------------------
CREATE TABLE case_report_recipient (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id  UUID        NOT NULL REFERENCES case_report_definition (id) ON DELETE CASCADE,

    target_type    VARCHAR(12) NOT NULL CHECK (target_type IN ('LINE_GROUP', 'LINE_USER')),
    target_id      TEXT        NOT NULL,
    label          TEXT,                          -- 'AOT ops group' - for the console, not LINE

    -- Belongs to the destination, not the report: the AOT message opens by
    -- addressing a named person ("เรียนคุณเอิร์ม และผู้บริหารโครงการทุกท่าน"), and the
    -- same report sent to the office group would greet someone else.
    greeting       TEXT,

    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP   NOT NULL DEFAULT now(),

    CONSTRAINT uq_case_report_recipient UNIQUE (definition_id, target_type, target_id)
);


-- -----------------------------------------------------------------------------
-- 7. case_branch_alias - messy board text to a clean code
--
-- Data rather than code because new spellings appear continuously and fixing one
-- must not need a developer. Real values live on the board today:
--
--   'ท่าอากาศยานสุวรรณภูมิ'      -> AOTGA / BKK
--   'ท่าอาศยานสุวรรณภูมิ BKK'    -> AOTGA / BKK   (missing ก, still a real ticket)
--   'AOTGAท่าอากาศยานดอนเมือง'  -> AOTGA / DMK
--   'Marko ระนอง 076'          -> Makro          (typo for Makro)
--
-- A ticket matching nothing gets no code, which surfaces it to staff. It must
-- never be guessed at: a wrong match puts one customer's cases in another
-- customer's report, and a sent LINE message cannot be recalled.
-- -----------------------------------------------------------------------------
CREATE TABLE case_branch_alias (
    id            UUID      PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Lower-cased and whitespace-collapsed by the application before matching,
    -- so 'Makro  ระนอง' and 'makro ระนอง' resolve to the same alias.
    match_value   TEXT      NOT NULL UNIQUE,

    project_code  TEXT,                          -- AOTGA | MK | Makro | Yayoi
    branch_code   TEXT,                          -- BKK | DMK | CNX | HKT | SAT1

    -- ⚠️ Drives the SLA threshold, so it is not decoration: 3 days inside greater
    -- Bangkok, 5 days everywhere else.
    --
    -- Neither board carries a location field - the province is buried in free text
    -- like 'โรบินสัน ราชบุรี' - and parsing it is unsafe: 'โลตัสหนองจอก' is Bangkok
    -- (Nong Chok district) with nothing in the string to say so. A human sets it
    -- once per branch instead.
    --
    -- NULL means unknown, and an unknown province must not be guessed at. The
    -- report shows the row without an SLA colour so somebody fills this in, rather
    -- than quietly telling a customer their case is on time.
    province      TEXT,                          -- 'Bangkok' | 'Rayong' | 'Songkhla' | ...

    -- Where the province came from, and whether a human has agreed with it. AI can
    -- read most Thai branch names ('เซ็นทรัลหาดใหญ่' is in Songkhla; 'โลตัสหนองจอก' is
    -- Bangkok despite looking upcountry), but roughly one branch in five is a bare
    -- company name - 'MK CK5', 'บริษัท IFS' - with no location in it at all.
    --
    -- An unconfirmed AI province is still used, and shown as provisional: a blank
    -- SLA cell on the first report after a new branch appears helps nobody. Once
    -- province_source is 'STAFF' the resolver must never overwrite it.
    province_source     VARCHAR(10) NOT NULL DEFAULT 'STAFF'
                                    CHECK (province_source IN ('STAFF', 'AI', 'IMPORT')),
    province_confirmed  BOOLEAN     NOT NULL DEFAULT FALSE,

    notes         TEXT,

    created_by    UUID      REFERENCES users (id),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);


-- -----------------------------------------------------------------------------
-- 8. case_robot_location - a synced mirror of the "Location raaspal robot" board
--
-- 1,089 robots, Delivery and Cleaning, each with a serial. It is the best source
-- of province because a serial is exact where a branch name is not: the cleaning
-- ticket whose branch reads only "SAM" resolves through serial
-- GS438-6260-H7R-J000 to "SAM Precision (Thailand) Limited, บ้านบึง ชลบุรี".
--
-- ⚠️ NOT unique on serial_number. The same robot appears at two sites when it has
-- been moved and the old row was never removed - BB52503e05312nG is filed under
-- both สระบุรี and สิงห์บุรี. Uniqueness is on the monday item, and a serial that
-- matches more than one row must be disambiguated by the ticket's own branch text,
-- not silently resolved to whichever row came back first.
--
-- ⚠️ Serials do not match cleanly either: a ticket carries L352507605060ZK where
-- this board has L352507606060zK - different case and a different digit. Compare
-- upper-cased and trimmed, and accept that a few will simply miss.
--
-- province is derived rather than stored upstream, because สาขา/โครงการ arrives in
-- three shapes: "สาขา บ้านบึง ชลบุรี" (province last, parseable), "Optical
-- Laboratory" (a place), and "K016" (a branch code). The Location column usually
-- carries the province when the dropdown does not, so both are kept verbatim.
-- -----------------------------------------------------------------------------
CREATE TABLE case_robot_location (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    source_item_id   TEXT        NOT NULL UNIQUE,
    serial_number    TEXT,
    serial_normalised TEXT,                      -- upper-cased, trimmed; what we join on

    site_name        TEXT,                       -- Location column
    branch_project   TEXT,                       -- สาขา/โครงการ dropdown
    branch_code      TEXT,                       -- รหัสสาขา; K### = Bonus Suki, M### = MK
    robot_type       TEXT,                       -- Delivery | Cleaning
    robot_model      TEXT,

    -- MONDAY is the goal: a Province dropdown maintained on the board itself, so
    -- the province is stated rather than inferred. PARSED / AI are what we fall
    -- back to until that column exists and is filled.
    province         TEXT,
    province_source  VARCHAR(10) NOT NULL DEFAULT 'PARSED'
                                 CHECK (province_source IN ('MONDAY', 'PARSED', 'AI', 'STAFF')),

    synced_at        TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_case_robot_location_serial ON case_robot_location (serial_normalised);


-- -----------------------------------------------------------------------------
-- 9. case_report_run - one generated report, and its audit trail
--
-- ⚠️ uq_case_report_run_day is the most important line in this migration. A LINE
-- message cannot be recalled by the API, and two app instances hitting the same
-- cron during the Render->Lightsail parallel run would otherwise double-send to
-- a customer. The database makes that impossible rather than merely unlikely.
-- -----------------------------------------------------------------------------
CREATE TABLE case_report_run (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id  UUID        NOT NULL REFERENCES case_report_definition (id),

    run_date       DATE        NOT NULL,

    status         VARCHAR(24) NOT NULL
                               CHECK (status IN ('GENERATING', 'AWAITING_APPROVAL',
                                                 'SENT', 'FAILED', 'DISCARDED')),

    ticket_count   INTEGER     NOT NULL DEFAULT 0,

    -- The generated rows, and what staff edit. The Excel is rendered FROM this at
    -- send time, never before: a file built at generation time would not contain
    -- the corrections made during review, and someone would approve a report and
    -- send a stale spreadsheet.
    rows_json      JSONB,

    ai_notes       TEXT,                          -- anything the model flagged as uncertain

    -- ⚠️ Stays NULL until approval, for the reason above.
    excel_path     TEXT,

    report_token   VARCHAR(64),                   -- opens at /report/{token}; LINE cannot carry the file itself

    generated_at   TIMESTAMP,
    approved_by    UUID        REFERENCES users (id),
    approved_at    TIMESTAMP,
    sent_at        TIMESTAMP,
    error_message  TEXT,

    created_at     TIMESTAMP   NOT NULL DEFAULT now(),

    CONSTRAINT uq_case_report_run_day UNIQUE (definition_id, run_date)
);

CREATE INDEX idx_case_report_run_status ON case_report_run (status, run_date DESC);
