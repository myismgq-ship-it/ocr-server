package com.gsafety.ocrtool.management;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 不可变的人工复核审计记录。
 *
 * <p>originalResult 保留机器结果，correctedResult 保留该修订的人工结果。</p>
 */
public record ReviewResponse(
        UUID reviewId,
        String planId,
        UUID taskId,
        int revisionNumber,
        Map<String, Object> originalResult,
        Map<String, Object> correctedResult,
        String reviewerId,
        String note,
        OffsetDateTime createdAt) {
}
