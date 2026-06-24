-- Public, shareable report links. A token maps to one robot + month; the public
-- page (/report/{token}) and the customer's monthly email both use this URL.
-- One stable link per robot per month (unique serial_number + report_month).
CREATE TABLE report_links (
    id            UUID PRIMARY KEY,
    token         VARCHAR(64) NOT NULL UNIQUE,
    serial_number VARCHAR(100) NOT NULL,
    report_month  VARCHAR(7) NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    CONSTRAINT uq_report_links_sn_month UNIQUE (serial_number, report_month)
);
