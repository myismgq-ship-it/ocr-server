-- Flyway baseline generated from the maintained PostgreSQL schema and seed data.

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
    claim_token UUID,
    stage VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    progress_percent INTEGER NOT NULL DEFAULT 0,
    attempt INTEGER NOT NULL DEFAULT 0,
    rule_version VARCHAR(64),
    retry_of_task_id UUID,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_plan_digitize_task_source_type CHECK (source_type IN ('UPLOAD', 'URL')),
    CONSTRAINT ck_plan_digitize_task_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_plan_digitize_task_progress
        CHECK (progress_percent BETWEEN 0 AND 100),
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

-- Initial segment rules.
INSERT INTO plan_segment_rule
    (rule_type, rule_code, canonical_name, alias, group_order, alias_order, enabled)
VALUES
    ('COMMAND', 'command_system', '指挥体系', '指挥体系',       10, 10, TRUE),
    ('COMMAND', 'command_system', '指挥体系', '组织指挥体系',   10, 20, TRUE),
    ('COMMAND', 'command_system', '指挥体系', '应急指挥体系',   10, 30, TRUE),
    ('COMMAND', 'command_system', '指挥体系', '指挥机构及职责', 10, 40, TRUE),
    ('COMMAND', 'command_system', '指挥体系', '组织机构及职责', 10, 50, TRUE),

    ('RESPONSE', 'level_1', '一级响应', '一级响应',       10, 10, TRUE),
    ('RESPONSE', 'level_1', '一级响应', '一级应急响应',   10, 20, TRUE),
    ('RESPONSE', 'level_1', '一级响应', 'Ⅰ级响应',        10, 30, TRUE),
    ('RESPONSE', 'level_1', '一级响应', 'I级响应',         10, 40, TRUE),
    ('RESPONSE', 'level_1', '一级响应', '特别重大响应',    10, 50, TRUE),

    ('RESPONSE', 'level_2', '二级响应', '二级响应',       20, 10, TRUE),
    ('RESPONSE', 'level_2', '二级响应', '二级应急响应',   20, 20, TRUE),
    ('RESPONSE', 'level_2', '二级响应', 'Ⅱ级响应',        20, 30, TRUE),
    ('RESPONSE', 'level_2', '二级响应', 'II级响应',        20, 40, TRUE),
    ('RESPONSE', 'level_2', '二级响应', '重大响应',        20, 50, TRUE),

    ('RESPONSE', 'level_3', '三级响应', '三级响应',       30, 10, TRUE),
    ('RESPONSE', 'level_3', '三级响应', '三级应急响应',   30, 20, TRUE),
    ('RESPONSE', 'level_3', '三级响应', 'Ⅲ级响应',        30, 30, TRUE),
    ('RESPONSE', 'level_3', '三级响应', 'III级响应',       30, 40, TRUE),
    ('RESPONSE', 'level_3', '三级响应', '较大响应',        30, 50, TRUE),

    ('RESPONSE', 'level_4', '四级响应', '四级响应',       40, 10, TRUE),
    ('RESPONSE', 'level_4', '四级响应', '四级应急响应',   40, 20, TRUE),
    ('RESPONSE', 'level_4', '四级响应', 'Ⅳ级响应',        40, 30, TRUE),
    ('RESPONSE', 'level_4', '四级响应', 'IV级响应',        40, 40, TRUE),
    ('RESPONSE', 'level_4', '四级响应', '一般响应',        40, 50, TRUE),

    ('WARNING', 'warning_level_1', '一级预警', '一级预警', 10, 10, TRUE),
    ('WARNING', 'warning_level_1', '一级预警', 'Ⅰ级预警', 10, 20, TRUE),
    ('WARNING', 'warning_level_1', '一级预警', '红色预警', 10, 30, TRUE),
    ('WARNING', 'warning_level_2', '二级预警', '二级预警', 20, 10, TRUE),
    ('WARNING', 'warning_level_2', '二级预警', 'Ⅱ级预警', 20, 20, TRUE),
    ('WARNING', 'warning_level_2', '二级预警', '橙色预警', 20, 30, TRUE),
    ('WARNING', 'warning_level_3', '三级预警', '三级预警', 30, 10, TRUE),
    ('WARNING', 'warning_level_3', '三级预警', 'Ⅲ级预警', 30, 20, TRUE),
    ('WARNING', 'warning_level_3', '三级预警', '黄色预警', 30, 30, TRUE),
    ('WARNING', 'warning_level_4', '四级预警', '四级预警', 40, 10, TRUE),
    ('WARNING', 'warning_level_4', '四级预警', 'Ⅳ级预警', 40, 20, TRUE),
    ('WARNING', 'warning_level_4', '四级预警', '蓝色预警', 40, 30, TRUE),

    ('SECTION', 'warning_scope', '预警章节', '监测预警', 10, 10, TRUE),
    ('SECTION', 'warning_scope', '预警章节', '预警响应', 10, 20, TRUE),
    ('SECTION', 'emergency_scope', '应急响应章节', '应急响应', 20, 10, TRUE),
    ('SECTION', 'emergency_scope', '应急响应章节', '分级响应', 20, 20, TRUE),
    ('SECTION', 'action_group_scope', '行动组章节', '工作组', 30, 10, TRUE),
    ('SECTION', 'action_group_scope', '行动组章节', '现场指挥部工作组', 30, 20, TRUE),

    ('MARKER', 'activation_condition', '启动条件标记', '启动条件', 10, 10, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '响应条件', 10, 20, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '发布条件', 10, 30, TRUE),
    ('MARKER', 'response_measure', '响应措施标记', '响应措施', 20, 10, TRUE),
    ('MARKER', 'response_measure', '响应措施标记', '应急措施', 20, 20, TRUE),
    ('MARKER', 'response_measure', '响应措施标记', '处置措施', 20, 30, TRUE),
    ('MARKER', 'inheritance', '措施继承标记', '基础上', 30, 10, TRUE),
    ('MARKER', 'group_lead', '牵头单位标记', '牵头', 40, 10, TRUE),
    ('MARKER', 'group_member', '成员单位标记', '组成', 50, 10, TRUE),
    ('MARKER', 'group_responsibility', '职责标记', '主要负责', 60, 10, TRUE),

    ('TAIL', 'response_tail', '响应结束标题', '启动条件调整', 10, 10, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '响应终止',     10, 20, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '综合保障',     10, 30, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '后期处置',     10, 40, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '附则',         10, 50, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '附件',         10, 60, TRUE)
ON CONFLICT (rule_type, rule_code, alias) DO UPDATE
SET canonical_name = EXCLUDED.canonical_name,
    group_order = EXCLUDED.group_order,
    alias_order = EXCLUDED.alias_order,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
