-- 仅用于 flyway_schema_history 中 V5 描述为 roman_emergency_response_aliases 的存量环境。
-- 先补齐应由正式 V5 创建的准确率表，再执行 `mvn flyway:repair` 对齐迁移历史。
CREATE TABLE IF NOT EXISTS plan_accuracy_sample (
    sample_id UUID PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    source_task_id UUID NOT NULL REFERENCES plan_digitize_task(task_id),
    review_id UUID NOT NULL REFERENCES plan_digitize_review(review_id),
    expected_result JSONB NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,
    CONSTRAINT uk_plan_accuracy_sample_review UNIQUE (review_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_accuracy_sample_active_source
    ON plan_accuracy_sample (source_task_id) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_plan_accuracy_sample_plan_history
    ON plan_accuracy_sample (plan_id, created_at DESC);

CREATE TABLE IF NOT EXISTS plan_accuracy_evaluation (
    evaluation_id UUID PRIMARY KEY,
    sample_id UUID NOT NULL REFERENCES plan_accuracy_sample(sample_id),
    source_task_id UUID NOT NULL REFERENCES plan_digitize_task(task_id),
    replay_task_id UUID NOT NULL UNIQUE REFERENCES plan_digitize_task(task_id),
    summary JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_plan_accuracy_evaluation_source
    ON plan_accuracy_evaluation (source_task_id, created_at DESC);
