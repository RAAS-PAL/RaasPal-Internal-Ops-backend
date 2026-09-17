-- The signed contract behind a deployment, as a PDF the staff attach from the
-- Contracts page.
--
-- One document, many deployments: a customer's contract usually covers several
-- robots (IFS One Siam is eight rows with one start and one end date), and the
-- staff should attach that PDF once. So the document is its own row, keyed by
-- customer, and each deployment points at the one that covers it. Replacing a
-- deployment's document re-points it; a document nothing points at any more is
-- deleted, from the bucket too.
--
-- The bytes are NOT here. They live in S3 (CONTRACT_S3_BUCKET) under storage_key.
-- A contract PDF is a megabyte or three, and a few hundred of them would eat the
-- database quota that the robot telemetry actually needs; the CSAT workbooks went
-- into a bytea column (V49) because there are exactly four of them.

CREATE TABLE contract_documents (
    id                  UUID PRIMARY KEY,
    customer_profile_id UUID         NOT NULL REFERENCES customer_profiles(id),
    file_name           VARCHAR(255) NOT NULL,
    content_type        VARCHAR(100) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    storage_key         VARCHAR(512) NOT NULL UNIQUE,
    uploaded_by         VARCHAR(255),
    uploaded_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_contract_documents_customer ON contract_documents (customer_profile_id);

ALTER TABLE deployments
    ADD COLUMN contract_document_id UUID REFERENCES contract_documents(id);

CREATE INDEX idx_deployments_contract_document ON deployments (contract_document_id);

COMMENT ON TABLE contract_documents IS
    'A signed contract PDF, stored in S3 under storage_key; shared by every deployment that points at it.';
COMMENT ON COLUMN deployments.contract_document_id IS
    'The contract PDF covering this deployment, if one has been attached. Null = none.';
