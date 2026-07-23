package com.gsafety.ocrtool.management;

import java.util.List;
import java.util.UUID;
/**
 * 两次预案数字化任务的差异结果。
 *
 * @param planId 预案 ID
 * @param fromTaskId 基准任务 ID
 * @param toTaskId 目标任务 ID
 * @param changes 叶子字段差异列表
 */

public record PlanComparisonResponse(
        String planId,
        UUID fromTaskId,
        UUID toTaskId,
        List<PlanChange> changes) {

    /**
     * 单个字段路径的变化。
     *
     * @param path 点分隔字段路径
     * @param changeType ADDED、REMOVED 或 MODIFIED
     * @param beforeValue 基准值
     * @param afterValue 目标值
     */
    public record PlanChange(
            String path,
            String changeType,
            Object beforeValue,
            Object afterValue) {
    }
}
