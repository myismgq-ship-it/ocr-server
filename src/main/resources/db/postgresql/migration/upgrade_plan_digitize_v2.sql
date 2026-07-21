-- Existing deployments: run this script first, then run ../data.sql.
ALTER TABLE plan_segment_rule DROP CONSTRAINT IF EXISTS ck_plan_segment_rule_type;
ALTER TABLE plan_segment_rule ADD CONSTRAINT ck_plan_segment_rule_type
    CHECK (rule_type IN ('COMMAND', 'RESPONSE', 'WARNING', 'SECTION', 'MARKER', 'TAIL'));

CREATE TABLE IF NOT EXISTS plan_digitize_task (
    task_id UUID PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    file_type VARCHAR(16),
    file_name VARCHAR(255),
    content_type VARCHAR(150),
    file_size BIGINT,
    source_url TEXT,
    source_path TEXT,
    status VARCHAR(16) NOT NULL,
    result JSONB,
    error_code VARCHAR(64),
    error_message TEXT,
    worker_id VARCHAR(128),
    heartbeat_at TIMESTAMPTZ,
    retry_of_task_id UUID,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_plan_digitize_task_source_type CHECK (source_type IN ('UPLOAD', 'URL')),
    CONSTRAINT ck_plan_digitize_task_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_plan_digitize_task_retry
        FOREIGN KEY (retry_of_task_id) REFERENCES plan_digitize_task(task_id)
);

ALTER TABLE plan_digitize_task ADD COLUMN IF NOT EXISTS file_type VARCHAR(16);

CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_digitize_task_active_plan
    ON plan_digitize_task (plan_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX IF NOT EXISTS idx_plan_digitize_task_queue
    ON plan_digitize_task (status, queued_at);
CREATE INDEX IF NOT EXISTS idx_plan_digitize_task_plan_history
    ON plan_digitize_task (plan_id, created_at DESC);
