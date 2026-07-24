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

COMMENT ON TABLE plan_segment_rule IS '预案章节与内容识别规则';
COMMENT ON COLUMN plan_segment_rule.id IS '规则主键';
COMMENT ON COLUMN plan_segment_rule.rule_type IS '规则类型：COMMAND、RESPONSE、WARNING、SECTION、MARKER、TAIL';
COMMENT ON COLUMN plan_segment_rule.rule_code IS '稳定的规则分组编码';
COMMENT ON COLUMN plan_segment_rule.canonical_name IS '规则对应的标准输出名称';
COMMENT ON COLUMN plan_segment_rule.alias IS '文档中可能出现的标题或内容别名';
COMMENT ON COLUMN plan_segment_rule.group_order IS '规则分组及响应级别输出顺序';
COMMENT ON COLUMN plan_segment_rule.alias_order IS '同一规则分组内的别名优先级';
COMMENT ON COLUMN plan_segment_rule.enabled IS '是否启用该规则';
COMMENT ON COLUMN plan_segment_rule.created_at IS '创建时间';
COMMENT ON COLUMN plan_segment_rule.updated_at IS '最后更新时间';

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
COMMENT ON COLUMN plan_digitize_task.task_id IS '任务主键';
COMMENT ON COLUMN plan_digitize_task.plan_id IS '外部预案业务ID，不设置预案主表外键';
COMMENT ON COLUMN plan_digitize_task.source_type IS '文档来源类型：UPLOAD上传、URL远程地址';
COMMENT ON COLUMN plan_digitize_task.file_type IS '识别后的文档类型，如PDF、DOC、DOCX、MHTML';
COMMENT ON COLUMN plan_digitize_task.file_name IS '源文档文件名';
COMMENT ON COLUMN plan_digitize_task.content_type IS '源文档MIME类型';
COMMENT ON COLUMN plan_digitize_task.file_size IS '源文档字节数';
COMMENT ON COLUMN plan_digitize_task.source_url IS '远程文档原始地址';
COMMENT ON COLUMN plan_digitize_task.source_path IS '任务存储目录中的源文件路径';
COMMENT ON COLUMN plan_digitize_task.status IS '任务状态：QUEUED、RUNNING、COMPLETED、FAILED、CANCELLED';
COMMENT ON COLUMN plan_digitize_task.result IS '完成后的预案数字化结构化结果';
COMMENT ON COLUMN plan_digitize_task.error_code IS '失败错误码';
COMMENT ON COLUMN plan_digitize_task.error_message IS '失败错误详情';
COMMENT ON COLUMN plan_digitize_task.worker_id IS '当前领取任务的执行实例标识';
COMMENT ON COLUMN plan_digitize_task.heartbeat_at IS '当前执行租约的最后心跳时间';
COMMENT ON COLUMN plan_digitize_task.claim_token IS '本次任务领取令牌，用于隔离不同执行尝试';
COMMENT ON COLUMN plan_digitize_task.stage IS '处理阶段：下载、解析、OCR、分段、持久化等';
COMMENT ON COLUMN plan_digitize_task.progress_percent IS '任务进度百分比，范围0至100';
COMMENT ON COLUMN plan_digitize_task.attempt IS '任务已领取执行的次数';
COMMENT ON COLUMN plan_digitize_task.rule_version IS '本次解析使用的规则快照版本';
COMMENT ON COLUMN plan_digitize_task.retry_of_task_id IS '被当前任务重试的原任务ID';
COMMENT ON COLUMN plan_digitize_task.queued_at IS '进入等待队列的时间';
COMMENT ON COLUMN plan_digitize_task.started_at IS '最近一次开始执行的时间';
COMMENT ON COLUMN plan_digitize_task.completed_at IS '任务完成、失败或取消时间';
COMMENT ON COLUMN plan_digitize_task.created_at IS '任务创建时间';
COMMENT ON COLUMN plan_digitize_task.updated_at IS '任务最后更新时间';

CREATE TABLE IF NOT EXISTS plan_catalog (
    plan_id VARCHAR(64) PRIMARY KEY,
    plan_code VARCHAR(64) NOT NULL,
    plan_name VARCHAR(256) NOT NULL,
    category VARCHAR(128),
    responsible_department VARCHAR(256),
    version_label VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_plan_catalog_updated_at
    ON plan_catalog (updated_at DESC);

COMMENT ON TABLE plan_catalog IS '预案目录及工作台展示元数据';
COMMENT ON COLUMN plan_catalog.plan_id IS '预案业务主键，与数字化任务plan_id保持一致';
COMMENT ON COLUMN plan_catalog.plan_code IS '预案业务编码';
COMMENT ON COLUMN plan_catalog.plan_name IS '预案名称';
COMMENT ON COLUMN plan_catalog.category IS '事件分类，如自然灾害、事故灾难、公共卫生事件';
COMMENT ON COLUMN plan_catalog.responsible_department IS '预案编制或主管部门';
COMMENT ON COLUMN plan_catalog.version_label IS '预案版本标识';
COMMENT ON COLUMN plan_catalog.created_at IS '目录创建时间';
COMMENT ON COLUMN plan_catalog.updated_at IS '目录最后更新时间';
