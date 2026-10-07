-- =============================================================================
-- V66 - The last step of an AOTGA case, which only RAASPAL can record: claiming
--       the old part back to the manufacturer
--
-- The AOT team's sheet carries a case from the report to the moment AOT sends
-- the old part back - the row turns blue. Claiming that part from the
-- manufacturer happens on our side, so the console records it here, one row per
-- AOT ticket.
--
-- old_part_back_on: the day the sync first saw the row turn blue; null when the
--                   row was already blue before the tracker saw it.
-- claimed_on, note, claimed_by: the claim; null until recorded, and cleared
--                   again by an undo.
--
-- No foreign key to case_ticket: the ticket number is the sheet's own id, and
-- the claim should outlive a re-link of the sheet.
--
-- Additive only.
-- =============================================================================
CREATE TABLE aotga_claim (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    spreadsheet_id   TEXT         NOT NULL,
    ticket_no        TEXT         NOT NULL,
    old_part_back_on DATE,
    claimed_on       DATE,
    note             TEXT,
    claimed_by       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_aotga_claim_ticket UNIQUE (spreadsheet_id, ticket_no)
);
