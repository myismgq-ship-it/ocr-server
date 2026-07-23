-- 预案目录保存工作台展示所需的业务元数据，避免前端写死具体预案。
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

-- 将升级前已经存在的任务回填为目录。无法从任务得知的业务字段保持明确的“未配置”状态，
-- 避免继续显示与真实任务无关的演示预案信息。
INSERT INTO plan_catalog (
    plan_id, plan_code, plan_name, category, responsible_department,
    version_label, created_at, updated_at)
SELECT
    latest.plan_id,
    latest.plan_id,
    COALESCE(
        NULLIF(regexp_replace(COALESCE(latest.result_file_name, latest.file_name, ''), '\.[^.]+$', ''), ''),
        latest.plan_id),
    '未分类',
    '未配置',
    '未标注',
    latest.created_at,
    latest.updated_at
FROM (
    SELECT DISTINCT ON (plan_id)
        plan_id,
        file_name,
        result ->> 'fileName' AS result_file_name,
        created_at,
        updated_at
    FROM plan_digitize_task
    ORDER BY plan_id, updated_at DESC
) latest
ON CONFLICT (plan_id) DO NOTHING;
