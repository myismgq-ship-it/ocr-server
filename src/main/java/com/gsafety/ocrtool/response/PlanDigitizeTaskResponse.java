package com.gsafety.ocrtool.response;

import java.time.OffsetDateTime;
import java.util.UUID;
/**
 * 异步任务详情响应。
 *
 * <p>保留原有字段，只追加阶段、进度、尝试次数、更新时间和规则版本。</p>
 */

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
        /** 是否因同一预案已有活动任务而复用。 */
        boolean reused,
        /** 完成时的结构化结果；执行中、失败或结果过期时为空。 */
        PlanDigitizeResponse result,
        /** 当前处理阶段。 */
        String stage,
        /** 0 到 100 的总体进度。 */
        int progressPercent,
        /** 累计领取执行次数。 */
        int attempt,
        /** 最近一次状态、心跳或进度更新时间。 */
        OffsetDateTime updatedAt,
        /** 生成结果时使用的规则快照版本。 */
        String ruleVersion) {

    public PlanDigitizeTaskResponse(
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
        this(
                taskId, planId, status, statusName, fileName, queuedAt, startedAt, completedAt,
                errorCode, errorMessage, reused, result, status, "COMPLETED".equals(status) ? 100 : 0,
                0, completedAt == null ? queuedAt : completedAt, null);
    }
}
