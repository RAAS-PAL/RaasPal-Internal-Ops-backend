-- Corrective Maintenance (CM) reports.
--
-- RAASPAL technicians log cleaning-robot service tickets in Monday.com. Producing
-- the customer-facing "รายงานการซ่อมบำรุงแก้ไข" used to mean copying that ticket into
-- an Excel template by hand and printing it. This table stores the structured
-- result instead, so a report can be searched, reopened, and reprinted later.
--
-- Every field except customer_name is nullable: a technician's ticket is often
-- partial (no serial number recorded, no test result yet), and refusing to save
-- an incomplete report would push people straight back to Excel.
--
-- Note V26 is deliberately skipped — it is reserved for the partner live-status
-- feature, which was designed before this one but is not yet built.
CREATE TABLE cm_reports (
    id                 UUID PRIMARY KEY,
    report_date        DATE NOT NULL,
    ticket_no          VARCHAR(64),
    customer_name      TEXT NOT NULL,
    technician_name    TEXT,
    robot_model        VARCHAR(255),
    serial_number      VARCHAR(128),
    cause_detail       TEXT,
    inspection_result  TEXT,
    -- Newline-separated steps. They render as a numbered list inside one table
    -- cell and are never queried individually, so a child table would buy nothing.
    corrective_actions TEXT,
    test_result        TEXT,
    -- The original Monday.com paste. Kept so a bad AI parse can be re-run without
    -- asking the technician to go back and copy the ticket again.
    source_text        TEXT,
    -- Signature photos, uploaded per report and stored as base64 data: URIs.
    -- Deliberately NOT routed through FileUploadService: that writes to local disk,
    -- and Render's disk is ephemeral, so a redeploy would silently break reprints of
    -- past reports. The client downscales to ~600px before upload, so these are tens
    -- of KB; the service enforces a hard 512KB ceiling.
    provider_signature TEXT,
    receiver_signature TEXT,
    created_by         UUID REFERENCES users(id),
    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL
);

-- The history list is "newest first" by default.
CREATE INDEX idx_cm_reports_created_at ON cm_reports (created_at DESC);
-- The two identifiers staff actually search a past report by.
CREATE INDEX idx_cm_reports_ticket_no ON cm_reports (ticket_no);
CREATE INDEX idx_cm_reports_serial ON cm_reports (serial_number);
