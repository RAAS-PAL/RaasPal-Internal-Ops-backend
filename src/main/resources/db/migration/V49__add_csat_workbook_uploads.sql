-- =============================================================================
-- V49 - CSAT survey workbooks, uploaded through the console and kept here
--
-- CSAT is the one RE KPI with no monday source: the RE team tallies the
-- post-job phone survey into four workbooks by hand, once a month. Until now
-- the backend read those from an S3 bucket the team uploaded to, or from a
-- folder on the machine running it. The bucket is gone - nine settings and a
-- key pair to keep four small files that change monthly - and this table
-- replaces it. The console uploads a workbook; the bytes live here.
--
-- Why the database and not the uploads volume the API already mounts: that
-- volume lives on the Lightsail instance and is only captured by a manual
-- snapshot, while the database is a separate host with its own backups. The
-- workbook IS the audit trail for a number that goes on a board deck, so it
-- has to be restorable together with the figures computed from it. At four
-- files a month this costs the database nothing.
--
-- Append-only. A new upload for a survey does not update or delete the one
-- before it, so "which file produced June's Top Box, and who replaced it" stays
-- answerable. "Restore the old one" is a fresh upload of those bytes, never an
-- edit of history.
--
-- There is deliberately no is_current column. The current workbook for a survey
-- is the most recent row for it, derived at read time, so deleting a row - a
-- wrong file uploaded by mistake - makes the one before it current again with
-- no flag left pointing at a row that no longer exists.
-- =============================================================================

CREATE TABLE kpi_csat_workbook (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- INSTALLATION | PM | CM_CLEANING | CM_DELIVERY, from CsatStream.detect():
    -- the sheet title first, the file name second. Never null - a workbook whose
    -- survey cannot be told is refused at upload rather than stored to be
    -- silently ignored by every later read.
    stream        VARCHAR(16)  NOT NULL,

    file_name     TEXT         NOT NULL,
    size_bytes    BIGINT       NOT NULL,

    -- SHA-256 of the bytes. Re-uploading a file identical to the survey's
    -- current one is answered "already the current file" instead of adding a row
    -- that cannot be told apart from the one above it.
    sha256        CHAR(64)     NOT NULL,

    content       BYTEA        NOT NULL,

    -- Upload time, not the file's own mtime: copying a file or downloading it
    -- from Drive resets mtime, whereas uploading is someone saying "this is the
    -- one now". This is what orders history and picks the current row.
    uploaded_at   TIMESTAMP    NOT NULL DEFAULT now(),

    -- Who to ask about a number. ON DELETE SET NULL: removing a user must not
    -- take the record of which workbook produced a published figure with it.
    uploaded_by   UUID         REFERENCES users(id) ON DELETE SET NULL,

    note          TEXT
);

-- Serves both reads: the current row per survey (DISTINCT ON stream) and the
-- history list, newest first.
CREATE INDEX idx_kpi_csat_workbook_stream_time ON kpi_csat_workbook (stream, uploaded_at DESC);

COMMENT ON TABLE  kpi_csat_workbook IS
    'Uploaded CSAT survey workbooks, append-only. Current = latest row per stream.';
COMMENT ON COLUMN kpi_csat_workbook.content IS
    'Workbook bytes. Never selected by the history list - projections only, or a page of it drags megabytes through a pool capped at DB_POOL_MAX.';
