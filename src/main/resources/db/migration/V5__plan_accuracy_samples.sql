-- 人工复核后的结果自动沉淀为准确率样本；同一源任务只保留一个当前生效样本。
CREATE TABLE plan_accuracy_sample (
    sample_id UUID PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    source_task_id UUID NOT NULL,
    review_id UUID NOT NULL,
    expected_result JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMPTZ,
    CONSTRAINT fk_plan_accuracy_sample_task
        FOREIGN KEY (source_task_id) REFERENCES plan_digitize_task(task_id),
    CONSTRAINT fk_plan_accuracy_sample_review
        FOREIGN KEY (review_id) REFERENCES plan_digitize_review(review_id),
    CONSTRAINT ck_plan_accuracy_sample_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT uk_plan_accuracy_sample_review UNIQUE (review_id)
);

CREATE UNIQUE INDEX uk_plan_accuracy_sample_active_source
    ON plan_accuracy_sample (source_task_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_plan_accuracy_sample_plan_history
    ON plan_accuracy_sample (plan_id, created_at DESC);

-- 重跑完成后保存结构覆盖率；这是规则回归指标，不把它冒充为人工语义准确率。
CREATE TABLE plan_accuracy_evaluation (
    evaluation_id UUID PRIMARY KEY,
    sample_id UUID NOT NULL REFERENCES plan_accuracy_sample(sample_id),
    source_task_id UUID NOT NULL REFERENCES plan_digitize_task(task_id),
    replay_task_id UUID NOT NULL UNIQUE REFERENCES plan_digitize_task(task_id),
    summary JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_plan_accuracy_evaluation_source
    ON plan_accuracy_evaluation (source_task_id, created_at DESC);

COMMENT ON TABLE plan_accuracy_sample IS '由人工复核结果自动生成的预案准确率样本';
COMMENT ON COLUMN plan_accuracy_sample.source_task_id IS '产生原始机器结果的任务ID';
COMMENT ON COLUMN plan_accuracy_sample.review_id IS '生成该样本的人工复核版本ID';
COMMENT ON COLUMN plan_accuracy_sample.expected_result IS '人工确认后的结构化期望结果';
COMMENT ON COLUMN plan_accuracy_sample.status IS '样本状态：ACTIVE当前生效，ARCHIVED历史版本';
COMMENT ON TABLE plan_accuracy_evaluation IS '历史任务按当前规则重跑后的结构覆盖率评测记录';
COMMENT ON COLUMN plan_accuracy_evaluation.summary IS '评测摘要，包含期望项、命中项、缺失项和结构覆盖率';
