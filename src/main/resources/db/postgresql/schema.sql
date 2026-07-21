CREATE TABLE IF NOT EXISTS plan_segment_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_type VARCHAR(16) NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    canonical_name VARCHAR(100) NOT NULL,
    alias VARCHAR(100) NOT NULL,
    group_order INTEGER NOT NULL DEFAULT 0,
    alias_order INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_plan_segment_rule_type
        CHECK (rule_type IN ('COMMAND', 'RESPONSE', 'WARNING', 'SECTION', 'MARKER', 'TAIL')),
    CONSTRAINT uk_plan_segment_rule_alias
        UNIQUE (rule_type, rule_code, alias)
);

CREATE INDEX IF NOT EXISTS idx_plan_segment_rule_enabled_order
    ON plan_segment_rule (rule_type, enabled, group_order, alias_order);

COMMENT ON TABLE plan_segment_rule IS '预案章节识别规则';
COMMENT ON COLUMN plan_segment_rule.rule_type IS '规则类型：COMMAND、RESPONSE、WARNING、SECTION、MARKER、TAIL';
COMMENT ON COLUMN plan_segment_rule.rule_code IS '稳定的规则分组编码';
COMMENT ON COLUMN plan_segment_rule.canonical_name IS '标准输出名称';
COMMENT ON COLUMN plan_segment_rule.alias IS '文档中可能出现的标题别名';
COMMENT ON COLUMN plan_segment_rule.group_order IS '规则分组及响应级别输出顺序';
COMMENT ON COLUMN plan_segment_rule.alias_order IS '同一规则分组内的别名优先级';

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

CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_digitize_task_active_plan
    ON plan_digitize_task (plan_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX IF NOT EXISTS idx_plan_digitize_task_queue
    ON plan_digitize_task (status, queued_at);

CREATE INDEX IF NOT EXISTS idx_plan_digitize_task_plan_history
    ON plan_digitize_task (plan_id, created_at DESC);

COMMENT ON TABLE plan_digitize_task IS '预案数字化异步任务';
COMMENT ON COLUMN plan_digitize_task.plan_id IS '外部预案业务ID，不设置预案主表外键';
COMMENT ON COLUMN plan_digitize_task.result IS '完成后的预案数字化结构化结果';
