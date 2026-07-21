package com.gsafety.ocrtool.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlanDigitizeTaskResponse(
        UUID taskId,
        String planId,
        String status,
        String statusName,
        String fileName,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String errorCode,
        String errorMessage,
        boolean reused,
        PlanDigitizeResponse result) {
}
