-- =============================================================================
-- V61 - Where a Google Sheet case source lives, editable from the console
--
-- The AOT team logs its cases in its own Google Sheet. Which sheet, which tab and
-- which columns mean "case id" and "closed" used to be deploy-time config; staff
-- now paste the sheet's link and pick the columns in Tools -> AOT sheet, and can
-- change them without a redeploy.
--
-- One row per source, keyed by source_key ('AOT'), so a second sheet is an INSERT.
-- The Google credentials are NOT here: a service-account key is a secret and stays
-- in the environment (GOOGLE_SHEETS_CREDENTIALS).
--
-- Additive only.
-- =============================================================================
CREATE TABLE case_source_sheet (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_key         VARCHAR(32)  NOT NULL,
    -- As pasted; spreadsheet_id is what the API is called with.
    sheet_url          TEXT         NOT NULL,
    spreadsheet_id     TEXT         NOT NULL,
    tab                TEXT         NOT NULL DEFAULT 'Case',
    header_row         INTEGER      NOT NULL DEFAULT 1 CHECK (header_row >= 1),
    row_id_header      TEXT,
    status_header      TEXT,
    -- Comma-separated values of status_header that mean closed.
    closed_statuses    TEXT,
    close_date_header  TEXT,
    sync_enabled       BOOLEAN      NOT NULL DEFAULT false,
    updated_by         TEXT,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_case_source_sheet_key UNIQUE (source_key)
);
