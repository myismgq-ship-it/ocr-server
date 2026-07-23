package com.gsafety.ocrtool.plan.task;

import java.time.OffsetDateTime;
import java.util.UUID;
/**
 * 预案数字化任务的完整持久化快照。
 *
 * <p>{@code workerId + claimToken} 共同标识一次领取尝试；执行者只有同时持有二者，
 * 才能更新心跳、进度和最终状态。</p>
 */

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
        /** 当前领取尝试的唯一令牌，重新领取后旧令牌立即失效。 */
        UUID claimToken,
        OffsetDateTime heartbeatAt,
        /** 当前处理阶段，取值对应 ProcessingStage。 */
        String stage,
        /** 面向调用方展示的总体进度，完成前最大为 99。 */
        int progressPercent,
        /** 已被领取执行的累计次数。 */
        int attempt,
        /** 本次解析使用的不可变规则快照版本。 */
        String ruleVersion,
        /** 若任务由失败重试产生，指向原始失败任务。 */
        UUID retryOfTaskId,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public PlanDigitizeTask(
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
        this(
                taskId, planId, sourceType, fileType, fileName, contentType, fileSize,
                sourceUrl, sourcePath, status, resultJson, errorCode, errorMessage,
                workerId, null, heartbeatAt, defaultStage(status), status == PlanDigitizeTaskStatus.COMPLETED ? 100 : 0,
                workerId == null ? 0 : 1, null, retryOfTaskId, queuedAt, startedAt, completedAt, createdAt, updatedAt);
    }

    private static String defaultStage(PlanDigitizeTaskStatus status) {
        return switch (status) {
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            default -> "QUEUED";
        };
    }
}
