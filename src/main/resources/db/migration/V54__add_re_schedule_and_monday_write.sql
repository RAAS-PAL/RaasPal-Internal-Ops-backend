-- RE assignment, second step: approving now writes the engineer into the board's RE column,
-- and an engineer can be booked on a job for several days so they are not suggested for
-- other work on those days.
--
-- Additive only.

-- Days an engineer is booked on a job. The board has one "RE Action" date per ticket and no
-- timeline, so a multi-day job is entered here - with the approval, or later from the
-- Engineers tab. item_id is optional (a booking can be for work that has no ticket); a
-- booking for a ticket stops counting once that ticket is finished.
CREATE TABLE re_schedule (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    engineer_id    UUID        NOT NULL REFERENCES re_engineer(id),
    board_id       TEXT,
    item_id        TEXT,
    assignment_id  UUID        REFERENCES re_assignment(id),
    starts_on      DATE        NOT NULL,
    ends_on        DATE        NOT NULL CHECK (ends_on >= starts_on),
    note           TEXT,
    created_by     TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_re_schedule_engineer ON re_schedule (engineer_id, starts_on, ends_on);
CREATE INDEX ix_re_schedule_item ON re_schedule (board_id, item_id);

-- What happened on monday for an approval:
--   NOT_WRITTEN  recorded only (monday writes switched off, or approved before V54)
--   WRITTEN      the engineer was put into the RE column
--   CLEARED      cancelled, and the RE column was emptied again
--   LEFT         cancelled, but monday already showed someone else, so it was left alone
ALTER TABLE re_assignment ADD COLUMN monday_status TEXT NOT NULL DEFAULT 'NOT_WRITTEN';
ALTER TABLE re_assignment ADD CONSTRAINT ck_re_assignment_monday_status
    CHECK (monday_status IN ('NOT_WRITTEN', 'WRITTEN', 'CLEARED', 'LEFT'));
ALTER TABLE re_assignment ADD COLUMN monday_detail TEXT;
ALTER TABLE re_assignment ADD COLUMN monday_written_at TIMESTAMPTZ;
