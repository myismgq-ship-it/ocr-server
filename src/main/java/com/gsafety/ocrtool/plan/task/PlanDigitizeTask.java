package com.gsafety.ocrtool.plan.task;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlanDigitizeTask(
        UUID taskId,
        String planId,
        PlanDigitizeTaskSourceType sourceType,
        String fileType,
        String fileName,
        String contentType,
        Long fileSize,
        String sourceUrl,
        String sourcePath,
        PlanDigitizeTaskStatus status,
        String resultJson,
        String errorCode,
        String errorMessage,
        String workerId,
        OffsetDateTime heartbeatAt,
        UUID retryOfTaskId,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
