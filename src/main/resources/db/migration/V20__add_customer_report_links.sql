-- Public, shareable report links covering ALL of a customer's robots for one
-- month. The monthly email sends one of these (not a per-robot report_links
-- token), so the customer gets one link that shows every robot's report.
-- One stable link per customer per month (unique customer_profile_id + report_month).
CREATE TABLE customer_report_links (
    id                  UUID PRIMARY KEY,
    token               VARCHAR(64) NOT NULL UNIQUE,
    customer_profile_id UUID NOT NULL REFERENCES customer_profiles(id),
    report_month        VARCHAR(7) NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT uq_customer_report_links_customer_month UNIQUE (customer_profile_id, report_month)
);
