-- RE assignment for corrective-maintenance tickets (Cleaning Tickets board first).
--
-- Phase 1 is "suggest, then the Senior RE approves": the system proposes the best
-- qualified, least-loaded engineer for each unassigned CM ticket; a person approves,
-- picks an alternative or rejects. Nothing is written to monday in this phase - the
-- Senior RE sets the RE there and the next refresh confirms it.
--
-- Additive only. No job is enabled by this migration.

-- ─── People ──────────────────────────────────────────────────────────────────

-- The RE team. Added and edited in the console; never deleted (history refers to them),
-- only deactivated. monday_user_id links an engineer to the People column on the board.
CREATE TABLE re_engineer (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name        TEXT         NOT NULL,
    nickname         TEXT,
    email            TEXT,
    monday_user_id   TEXT         UNIQUE,
    active           BOOLEAN      NOT NULL DEFAULT true,
    -- Open work allowed before the engineer counts as busy, in load units (see the
    -- status weights in application.properties: an active ticket is 1.0, a ticket
    -- waiting in Check is 0.25).
    max_load         NUMERIC(5,2) NOT NULL DEFAULT 6 CHECK (max_load > 0),
    note             TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_re_engineer_email ON re_engineer (lower(email)) WHERE email IS NOT NULL;

-- Who may manage skills and approve assignments, besides ADMIN. Granted by an ADMIN.
CREATE TABLE re_manager_grant (
    user_id     UUID        PRIMARY KEY REFERENCES users(id),
    granted_by  TEXT        NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Leave and off days. A day inside any window makes the engineer unavailable for new work.
CREATE TABLE re_leave (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    engineer_id  UUID        NOT NULL REFERENCES re_engineer(id),
    starts_on    DATE        NOT NULL,
    ends_on      DATE        NOT NULL CHECK (ends_on >= starts_on),
    note         TEXT,
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_re_leave_engineer ON re_leave (engineer_id, starts_on, ends_on);

-- ─── Skill matrix ────────────────────────────────────────────────────────────

-- The workbook's columns, as stable codes. A new skill is a new row; codes are never reused.
CREATE TABLE re_skill_definition (
    code        TEXT    PRIMARY KEY,
    group_code  TEXT    NOT NULL,   -- SOFT | OVERALL | INSTALLATION | PM | CM | EXPERTISE | MODEL
    board_type  TEXT    NOT NULL CHECK (board_type IN ('CLEANING', 'DELIVERY', 'COMMON')),
    label       TEXT    NOT NULL,
    ordinal     INTEGER NOT NULL UNIQUE
);

INSERT INTO re_skill_definition (code, group_code, board_type, label, ordinal) VALUES
 ('COMMUNICATION',            'SOFT',         'COMMON',   'การสื่อสาร',         1),
 ('SERVICE',                  'SOFT',         'COMMON',   'การบริการ',          2),
 ('TRAINING',                 'SOFT',         'COMMON',   'Training',           3),
 ('OVERALL_CLEANING',         'OVERALL',      'CLEANING', 'Cleaning',           4),
 ('OVERALL_DELIVERY',         'OVERALL',      'DELIVERY', 'Delivery',           5),
 ('INSTALLATION_CLEANING',    'INSTALLATION', 'CLEANING', 'Cleaning',           6),
 ('INSTALLATION_DELIVERY',    'INSTALLATION', 'DELIVERY', 'Delivery',           7),
 ('PM_CLEANING',              'PM',           'CLEANING', 'Cleaning',           8),
 ('PM_DELIVERY',              'PM',           'DELIVERY', 'Delivery',           9),
 ('CM_CLEANING',              'CM',           'CLEANING', 'Cleaning',          10),
 ('CM_DELIVERY',              'CM',           'DELIVERY', 'Delivery',          11),
 ('ELECTRICAL_CLEANING',      'EXPERTISE',    'CLEANING', 'Electrical',        12),
 ('MECHANICAL_CLEANING',      'EXPERTISE',    'CLEANING', 'Mechanical',        13),
 ('SOFTWARE_CLEANING',        'EXPERTISE',    'CLEANING', 'Software / Robot',  14),
 ('TROUBLESHOOTING_CLEANING', 'EXPERTISE',    'CLEANING', 'Troubleshooting',   15),
 ('ELECTRICAL_DELIVERY',      'EXPERTISE',    'DELIVERY', 'Electrical',        16),
 ('MECHANICAL_DELIVERY',      'EXPERTISE',    'DELIVERY', 'Mechanical',        17),
 ('SOFTWARE_DELIVERY',        'EXPERTISE',    'DELIVERY', 'Software / Robot',  18),
 ('TROUBLESHOOTING_DELIVERY', 'EXPERTISE',    'DELIVERY', 'Troubleshooting',   19),
 ('PHANTAS',                  'MODEL',        'CLEANING', 'Phantas',           20),
 ('M40',                      'MODEL',        'CLEANING', 'M40',               21),
 ('OMNIE',                    'MODEL',        'CLEANING', 'OMNIE',             22),
 ('BEETLE',                   'MODEL',        'CLEANING', 'Beetle',            23),
 ('M75',                      'MODEL',        'CLEANING', 'M75',               24),
 ('C3',                       'MODEL',        'CLEANING', 'C3',                25),
 ('TITAN',                    'MODEL',        'CLEANING', 'Titan',             26),
 ('X_HUMAN',                  'MODEL',        'CLEANING', 'X-Human',           27),
 ('PUDU',                     'MODEL',        'DELIVERY', 'Pudu',              28),
 ('PUDU_2',                   'MODEL',        'DELIVERY', 'Pudu 2',            29),
 ('KETTY',                    'MODEL',        'DELIVERY', 'Ketty',             30),
 ('BELLA',                    'MODEL',        'DELIVERY', 'Bella',             31),
 ('D_150',                    'MODEL',        'DELIVERY', 'D-150',             32),
 ('T10',                      'MODEL',        'DELIVERY', 'T10',               33),
 ('CADEBOT',                  'MODEL',        'DELIVERY', 'Cadebot',           34);

-- One row per save or import: who changed the matrix, when, and why.
CREATE TABLE re_matrix_revision (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    label       TEXT        NOT NULL UNIQUE,
    source      TEXT        NOT NULL CHECK (source IN ('EXCEL', 'CONSOLE')),
    file_hash   TEXT,
    reason      TEXT        NOT NULL,
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The levels in force now. level_value NULL = "-" / not assessed, which never qualifies.
CREATE TABLE re_skill_level (
    engineer_id  UUID        NOT NULL REFERENCES re_engineer(id),
    skill_code   TEXT        NOT NULL REFERENCES re_skill_definition(code),
    level_value  SMALLINT    CHECK (level_value BETWEEN 1 AND 4),
    revision_id  UUID        NOT NULL REFERENCES re_matrix_revision(id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (engineer_id, skill_code)
);
CREATE INDEX ix_re_skill_level_candidates ON re_skill_level (skill_code, level_value);

-- Every level change, append-only. Together with the revisions this answers
-- "what did this engineer's matrix say on a given day".
CREATE TABLE re_skill_change (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id  UUID        NOT NULL REFERENCES re_matrix_revision(id),
    engineer_id  UUID        NOT NULL REFERENCES re_engineer(id),
    skill_code   TEXT        NOT NULL REFERENCES re_skill_definition(code),
    old_level    SMALLINT,
    new_level    SMALLINT,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_re_skill_change_engineer ON re_skill_change (engineer_id, changed_at DESC);

-- Board "Type of Robot" label -> matrix model. Only MAPPED labels are auto-suggested;
-- MANUAL and UNCONFIRMED stay with the Senior RE. New labels default to MANUAL.
CREATE TABLE re_model_mapping (
    board_id     TEXT        NOT NULL,
    label        TEXT        NOT NULL,
    disposition  TEXT        NOT NULL CHECK (disposition IN ('MAPPED', 'MANUAL', 'UNCONFIRMED')),
    skill_code   TEXT        REFERENCES re_skill_definition(code),
    note         TEXT,
    updated_by   TEXT        NOT NULL DEFAULT 'seed',
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, label),
    CONSTRAINT ck_re_model_mapping_skill CHECK ((disposition = 'MAPPED') = (skill_code IS NOT NULL))
);

INSERT INTO re_model_mapping (board_id, label, disposition, skill_code, note) VALUES
 ('3451717331', 'Phantas 1.0',      'MAPPED',      'PHANTAS', NULL),
 ('3451717331', 'Phantas 1.1',      'MAPPED',      'PHANTAS', NULL),
 ('3451717331', 'Phantas 1.3',      'MAPPED',      'PHANTAS', NULL),
 ('3451717331', 'M40',              'MAPPED',      'M40',     NULL),
 ('3451717331', 'M40 Spray',        'MAPPED',      'M40',     NULL),
 ('3451717331', 'Omnie',            'MAPPED',      'OMNIE',   NULL),
 ('3451717331', 'Beetle',           'MAPPED',      'BEETLE',  NULL),
 ('3451717331', 'M75',              'MAPPED',      'M75',     NULL),
 ('3451717331', 'C3',               'MAPPED',      'C3',      NULL),
 ('3451717331', 'Scrubber 75',      'UNCONFIRMED', NULL,      'Same robot as M75? Senior RE to confirm'),
 ('3451717331', 'C30',              'UNCONFIRMED', NULL,      'Same robot as C3? Senior RE to confirm'),
 ('3451717331', 'M50',              'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'M50 PRO',          'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'M50 Disc Brush',   'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'M50 Roller Brush', 'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'X1',               'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'A1',               'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'T1',               'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'M111',             'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'OC-01',            'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'OC-02',            'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'Blend',            'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'Simple',           'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'SpiderBot',        'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'I-Scrub',          'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'Scooter',          'MANUAL',      NULL,      'Not in the skill matrix'),
 ('3451717331', 'ZARA',             'MANUAL',      NULL,      'Delivery robot on the cleaning board'),
 ('3451717331', 'KettyBot',         'MANUAL',      NULL,      'Delivery robot on the cleaning board'),
 ('3451717331', 'Charging Dock',    'MANUAL',      NULL,      'Accessory'),
 ('3451717331', 'Mobile Charging',  'MANUAL',      NULL,      'Accessory'),
 ('3451717331', 'IOT',              'MANUAL',      NULL,      'Accessory'),
 ('3451717331', 'IOT Sensor',       'MANUAL',      NULL,      'Accessory');

-- ─── Tickets and assignments ─────────────────────────────────────────────────

-- The open tickets as last read from monday, with just what assignment needs. Its own
-- table rather than case_ticket: the pending-case reports rely on case_ticket's
-- is_present meaning "in the All Case group", and this reads every group.
CREATE TABLE re_ticket (
    board_id           TEXT        NOT NULL,
    item_id            TEXT        NOT NULL,
    item_name          TEXT,
    group_title        TEXT,
    status             TEXT,
    sub_status         TEXT,
    model_label        TEXT,
    issue_level        TEXT,
    case_type          TEXT,
    service_mode       TEXT,
    serial_number      TEXT,
    customer           TEXT,
    branch             TEXT,
    main_issue         TEXT,
    open_date          DATE,
    action_date        DATE,
    -- [{"id":"123","name":"..."}] from the People column; empty = unassigned.
    people             JSONB       NOT NULL DEFAULT '[]',
    monday_updated_at  TIMESTAMPTZ,
    is_open            BOOLEAN     NOT NULL DEFAULT true,
    first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, item_id)
);
CREATE INDEX ix_re_ticket_open ON re_ticket (board_id, is_open);

-- A ticket the Senior RE took out of auto-suggestion ("reject"). Released = back in the queue.
CREATE TABLE re_ticket_hold (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id     TEXT        NOT NULL,
    item_id      TEXT        NOT NULL,
    reason       TEXT        NOT NULL,
    held_by      TEXT        NOT NULL,
    held_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by  TEXT,
    released_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_re_ticket_hold_active ON re_ticket_hold (board_id, item_id) WHERE released_at IS NULL;

-- An approved assignment. APPROVED = waiting for the RE to appear on monday;
-- CONFIRMED = seen there; CANCELLED = withdrawn in the console; SUPERSEDED = monday
-- shows someone else. At most one current (ended_at IS NULL) row per ticket.
CREATE TABLE re_assignment (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id           TEXT         NOT NULL,
    item_id            TEXT         NOT NULL,
    engineer_id        UUID         NOT NULL REFERENCES re_engineer(id),
    status             TEXT         NOT NULL CHECK (status IN ('APPROVED', 'CONFIRMED', 'CANCELLED', 'SUPERSEDED')),
    origin             TEXT         NOT NULL CHECK (origin IN ('SUGGESTION', 'ALTERNATIVE', 'MANUAL')),
    score              NUMERIC(7,2),
    required_level     SMALLINT,
    reason             TEXT         NOT NULL,
    -- The whole decision as the approver saw it: levels, load, candidates, exclusions.
    decision_snapshot  JSONB        NOT NULL,
    approved_by        TEXT         NOT NULL,
    approved_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,
    ended_at           TIMESTAMPTZ,
    ended_by           TEXT,
    email_status       TEXT         NOT NULL DEFAULT 'NOT_SENT'
                                    CHECK (email_status IN ('NOT_SENT', 'SENT', 'FAILED', 'DISABLED', 'NO_ADDRESS')),
    email_detail       TEXT,
    email_sent_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_re_assignment_current ON re_assignment (board_id, item_id) WHERE ended_at IS NULL;
CREATE INDEX ix_re_assignment_engineer ON re_assignment (engineer_id, approved_at DESC);

-- Everything a person did in the module, append-only.
CREATE TABLE re_event (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type  TEXT        NOT NULL,
    entity_id    TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    actor        TEXT        NOT NULL,
    detail       JSONB       NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_re_event_entity ON re_event (entity_type, entity_id, id DESC);

CREATE FUNCTION re_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER re_event_append_only BEFORE UPDATE OR DELETE ON re_event
    FOR EACH ROW EXECUTE FUNCTION re_append_only();
CREATE TRIGGER re_skill_change_append_only BEFORE UPDATE OR DELETE ON re_skill_change
    FOR EACH ROW EXECUTE FUNCTION re_append_only();
