-- =============================================================================
-- V69 - Public links to pending cases
--
-- A link opens, to anyone who has it and with no sign-in (the token is the
-- access), one of three things:
--   SHEET  a pending sheet's details page (mk, cleaning, makro, delivery,
--          on-hold): its latest stored copy, always current.
--   VIEW   a pending tab (internal, pcs, makro, ifs, mk, on-hold, aot) for the
--          period picked beside its calendar: the cases opened in it, live while
--          it runs and as at its last day once it is over; ALL = every open case.
--   CASE   one case (sheet + monday item id, or aotga + AOT ticket number): live
--          while open, and its last state marked closed once it leaves the list.
-- Nothing is ever generated for a visitor: links read the stored sheets only.
--
-- customers:  JSON array of customer names (the cases' Project) on a sheet or a
--             tab that mixes customers; NULL for every case.
-- scope:      VIEW on a tab of both boards: BOTH, CLEANING or DELIVERY.
-- cadence, period_from, period_to: VIEW - the period as picked; no days for ALL.
-- expires_at: set when shared (30 days unless chosen otherwise), can be moved;
--             revoked_at ends a link early ("Stop sharing").
--
-- Additive only.
-- =============================================================================
CREATE TABLE case_share_links (
    id             UUID         PRIMARY KEY,
    token          VARCHAR(64)  NOT NULL UNIQUE,
    kind           VARCHAR(8)   NOT NULL,
    sheet          VARCHAR(16),
    view_name      VARCHAR(16),
    scope          VARCHAR(10),
    customers      TEXT,
    cadence        VARCHAR(8),
    period_from    DATE,
    period_to      DATE,
    case_key       VARCHAR(64),
    created_by     UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ,
    view_count     INTEGER      NOT NULL DEFAULT 0,
    last_viewed_at TIMESTAMPTZ
);

CREATE INDEX idx_case_share_links_target ON case_share_links (kind, sheet, view_name, case_key);
