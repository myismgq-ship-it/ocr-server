CREATE TABLE IF NOT EXISTS ocr_template_revision (
    revision_id UUID PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL,
    revision_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    definition JSONB NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_ocr_template_revision_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT uk_ocr_template_revision_number
        UNIQUE (template_code, revision_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ocr_template_revision_published
    ON ocr_template_revision (template_code)
    WHERE status = 'PUBLISHED';

CREATE INDEX IF NOT EXISTS idx_ocr_template_revision_history
    ON ocr_template_revision (template_code, revision_number DESC);

CREATE TABLE IF NOT EXISTS plan_rule_revision (
    revision_id UUID PRIMARY KEY,
    revision_number INTEGER NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    definition JSONB NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_plan_rule_revision_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_rule_revision_published
    ON plan_rule_revision ((status))
    WHERE status = 'PUBLISHED';

CREATE TABLE IF NOT EXISTS plan_digitize_review (
    review_id UUID PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    task_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    original_result JSONB NOT NULL,
    corrected_result JSONB NOT NULL,
    reviewer_id VARCHAR(128) NOT NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_digitize_review_task
        FOREIGN KEY (task_id) REFERENCES plan_digitize_task(task_id),
    CONSTRAINT uk_plan_digitize_review_revision
        UNIQUE (task_id, revision_number)
);

CREATE INDEX IF NOT EXISTS idx_plan_digitize_review_history
    ON plan_digitize_review (plan_id, task_id, revision_number DESC);
