package com.gsafety.ocrtool.management;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 历史任务重跑后与人工样本对比的结构覆盖率评测结果。 */
public record PlanAccuracyEvaluationResponse(
        UUID evaluationId,
        UUID sampleId,
        UUID sourceTaskId,
        UUID replayTaskId,
        Map<String, Object> summary,
        OffsetDateTime createdAt) {
}
