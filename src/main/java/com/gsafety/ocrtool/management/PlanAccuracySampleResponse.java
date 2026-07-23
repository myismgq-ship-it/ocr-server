package com.gsafety.ocrtool.management;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 人工复核自动沉淀的准确率样本。
 *
 * <p>样本的期望结果始终来自不可变的复核记录，避免规则变更覆盖人工确认内容。</p>
 */
public record PlanAccuracySampleResponse(
        UUID sampleId,
        String planId,
        UUID sourceTaskId,
        UUID reviewId,
        Map<String, Object> expectedResult,
        String status,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime archivedAt) {
}
