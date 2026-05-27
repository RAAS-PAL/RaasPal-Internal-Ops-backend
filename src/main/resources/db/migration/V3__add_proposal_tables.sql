-- Add proposal template/history support and MVP recommendation option fields.

ALTER TABLE recommendation_items
    ADD COLUMN fit_level VARCHAR(50),
    ADD COLUMN proposal_title VARCHAR(500),
    ADD COLUMN proposal_summary TEXT,
    ADD COLUMN why_recommended TEXT,
    ADD COLUMN matched_requirements TEXT,
    ADD COLUMN business_value TEXT,
    ADD COLUMN limitations TEXT,
    ADD COLUMN missing_information TEXT,
    ADD COLUMN suggested_next_step TEXT;

CREATE TABLE proposal_templates (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    template_content TEXT         NOT NULL,
    content_format   VARCHAR(50)  NOT NULL DEFAULT 'TEXT',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by       UUID         REFERENCES users(id),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE generated_proposals (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recommendation_id      UUID         NOT NULL REFERENCES recommendations(id),
    recommendation_item_id UUID         NOT NULL REFERENCES recommendation_items(id),
    requirement_id         UUID         NOT NULL REFERENCES requirements(id),
    proposal_template_id   UUID         REFERENCES proposal_templates(id),
    title                  VARCHAR(255) NOT NULL,
    proposal_content       TEXT         NOT NULL,
    content_format         VARCHAR(50)  NOT NULL DEFAULT 'TEXT',
    status                 VARCHAR(20)  NOT NULL DEFAULT 'GENERATED',
    generated_by           UUID         REFERENCES users(id),
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_proposal_templates_active ON proposal_templates(is_active);
CREATE INDEX idx_generated_proposals_recommendation ON generated_proposals(recommendation_id);
CREATE INDEX idx_generated_proposals_recommendation_item ON generated_proposals(recommendation_item_id);
CREATE INDEX idx_generated_proposals_requirement ON generated_proposals(requirement_id);
CREATE INDEX idx_generated_proposals_template ON generated_proposals(proposal_template_id);
